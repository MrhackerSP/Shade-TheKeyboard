#!/usr/bin/env python3
"""
Trains the glide-decoder reranker and emits app/src/main/assets/swipe_model.json.

The bundled SlideDecoder ranks candidates with hand-tuned feature weights. This
script replaces the guesswork with fitted weights: it simulates tens of
thousands of human-like swipe traces over the bundled lexicon, extracts the
SAME raw features the Kotlin decoder computes in stage 2, and fits a ranker
whose score is blended into the hand-tuned score at inference time.

Pipeline:
  1. Build the candidate lexicon from assets (top words + a sampled slice of the
     long-tail dictionary), with Zipf-style counts mapped through the same
     log-normalised prior formula the app uses.
  2. Fit letter-bigram AND letter-trigram language models over the lexicon
     (frequency-weighted, Laplace-smoothed, the trigram interpolated with a
     back-off to the bigram model) and freeze them: the tables ship inside the
     model JSON and both the trainer and the Kotlin decoder score candidate
     words from those exact frozen numbers.
  3. For every training word, simulate several swipe traces (Catmull-Rom path
     through the key centres) with different noise seeds. Traces include the
     error modes real fingers produce: skipped keys, brief veers onto a
     neighbouring key, a global tilt on top of per-point drift, corner-cutting
     on sharp turns, little loops on doubled letters, starts that drift onto a
     neighbouring key, variable speed (fast stretches are sloppier than slow
     ones), occasional very clean and very sloppy swipes.
  4. For each trace, run the decoder's stage 1 in Python (start/end radius,
     length-ratio gate, DP letter alignment - vectorised across candidates) and
     keep the top candidates plus the true word.
  5. Extract stage-2 features for each candidate (shape distance, adherence,
     corner penalty, candidate distance, vowel deviation, typed-error counts,
     length ratio, prior, letter count, squared shape/adherence, total edit
     mass, start-key and end-key deviation, bigram and trigram plausibility,
     stage-1 DP alignment cost) - each mirrored 1:1 from SlideDecoder.kt.
  6. Hard-negative mining (two rounds): the fitted ranker reveals which
     training words still mis-rank; those words get extra simulated traces,
     then the refitted ranker mines a second batch.
  7. Fit two rankers on the same features - a linear logit and a small
     one-hidden-layer network over STANDARDISED features clipped to +/-CLIP_Z
     standard deviations (cross-entropy over candidates per trace with label
     smoothing, Adam, L2, early stopping on the word-disjoint hold-out),
     sweeping the hidden width, iteration count and learning rate - grid
     search the blend factor against the hold-out, and emit whichever model
     class wins as JSON (version 1 = linear, version 3 = standardised neural;
     version 2 = legacy raw neural, still loadable but no longer emitted).

Run from the repo root:  python3 scripts/train_swipe_model.py
Add --smoke for a fast end-to-end sanity run (tiny data, no mining).

Credit: the approach (train on simulated swipes, then rerank the decoder's
survivors with fitted weights) was inspired by FUTO Keyboard's trained glide
typing, https://github.com/futo-futo/keyboard - the first keyboard to ship a
fully on-device trained glide decoder. This trainer and the model it emits are
Shade's own work; no code or data from any other keyboard is used.
"""

import json
import math
import random
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
SMOKE = "--smoke" in sys.argv

# ------------------------------------------------------------------ constants
# Mirrors SlideDecoder's companion object. Keep in sync with SlideDecoder.kt.
SIGMA = 0.9
END_SIGMA = 0.5
ADH_TOL = 0.4
CORNER_FREE = 0.6
START_R = 1.1
END_R = 1.1
RMAX = 1.0
STAGE1_KEEP_EVAL = 16
SHAPE_N = 32
RES = 0.16
M_MIN = 24
M_MAX = 72
SWIPE_KEY_R = 0.6
W_SHAPE, W_ADH, W_CORNER = 3.0, 5.0, 1.0
W_LEN, W_LM = 1.6, 6.0
W_C_DIST, W_VOWEL, W_CHAR_NUM = 1.5, 0.5, 0.8
W_OMISSION, W_INSERTION, W_TRANSPOSITION = 0.8, 0.5, 0.2
W_NEARBY_SUB, W_GENERAL_SUB = 0.4, 1.0

# The names and the ORDER are a contract shared with SwipeModel.kt (which
# validates them on load) and SlideDecoder.kt (which fills the vector by
# index). "align" is the stage-1 DP alignment cost - the strongest single
# decoder signal, previously visible to the model only indirectly through the
# other features. "trigram" is the mean letter-trigram log-probability under
# the frozen table shipped in the JSON (the bigram analog of "bigram").
FEATURE_NAMES = ["shape", "adh", "corner", "cdist", "vowel", "edit",
                 "lenratio", "prior", "letters",
                 "shape2", "adh2", "editlen", "startd", "endd", "bigram",
                 "align", "trigram"]
NF = len(FEATURE_NAMES)
VOWELS = np.array([c in (0, 4, 8, 14, 20) for c in range(26)])

# Bigram language model (Laplace smoothing). The frozen log-probability table
# ships in the model JSON; the Kotlin decoder scores words from that exact
# table, so training-time and run-time features match to the last bit.
BIGRAM_ALPHA = 0.1

# Trigram LM: the context probability is interpolated with a back-off to the
# (frequency-weighted) bigram model, so sparse trigram contexts still score
# sensibly. Ships as a frozen 26^3 table alongside the bigram one.
TRIGRAM_ALPHA = 0.1
TRIGRAM_LAMBDA = 0.6

# Word weights for the language-model counts: the Zipf-style frequency estimate
# raised to LM_WEIGHT_POWER. Full counts would let the top few words swamp the
# tables; sqrt keeps the frequency ordering while leaving room for the tail's
# letter patterns ("xylophone" still counts, "the" just counts much more).
LM_WEIGHT_POWER = 0.5

# Standardised features are clipped to +/-CLIP_Z standard deviations before the
# hidden layer (here AND in SwipeModel.penalty): heavy-tailed features (adh2,
# editlen) otherwise saturate the tanh units and stall training.
CLIP_Z = 3.5

# Label smoothing over each trace's candidate set (fit_mlp): keeps logits
# calibrated so the reranker does not over-commit to easy traces.
EPS = 0.05

# ------------------------------------------------------------------ geometry
def build_geometry():
    """QWERTY key centres in key-pitch units (matches the app's normalisation)."""
    geo = {}
    rows = [
        ("qwertyuiop", 0.0, 0.5),
        ("asdfghjkl", 1.0, 0.75),
        ("zxcvbnm", 2.0, 1.25),
    ]
    for letters, y, x0 in rows:
        for i, ch in enumerate(letters):
            geo[ord(ch) - 97] = (x0 + i, y)
    return geo


GEO = build_geometry()
GX = np.array([GEO[c][0] for c in range(26)])
GY = np.array([GEO[c][1] for c in range(26)])
PAIR = np.full((26, 26), np.nan)
for a in range(26):
    for b in range(26):
        PAIR[a, b] = math.hypot(GX[a] - GX[b], GY[a] - GY[b])

# For each key, every other key ordered by distance (used for veer simulations).
NEIGHBOURS = [sorted((c for c in range(26) if c != a), key=lambda c: PAIR[a, c])
              for a in range(26)]


def collapse(word):
    """Ports GlideLexicon.build's path construction (apostrophes dropped,
    consecutive doubles collapsed). Returns (codes, ok)."""
    codes = []
    for ch in word:
        if ch in ("'", "-"):
            continue
        c = ord(ch) - 97 if "a" <= ch <= "z" else -1
        if c < 0:
            return None, False
        if not codes or codes[-1] != c:
            codes.append(c)
    return codes, 2 <= len(codes) <= 24 and len(word) <= 40


def doubled_positions(word):
    """Collapsed-path indices whose key the raw word hits twice in a row
    ("letter" -> [2] for the tt). These keys get a loop in some simulated
    traces, the way real fingers draw double letters."""
    out = []
    run = -1
    prev = None
    for ch in word:
        if ch in ("'", "-"):
            continue
        if "a" <= ch <= "z":
            if ch == prev:
                if run not in out:
                    out.append(run)
            else:
                run += 1
                prev = ch
    return out


def priors_from_counts(counts):
    logs = np.log(np.maximum(np.asarray(counts, dtype=float), 1.0))
    lo, hi = logs.min(), logs.max()
    span = hi - lo
    if span <= 1e-9:
        return np.full(len(counts), 0.5)
    return (logs - lo) / span


LEXICON_TAIL = 12000 if not SMOKE else 400
TAIL_TARGETS = 3600 if not SMOKE else 120


def load_lexicon():
    """Top words (frequency order) + a sample of the long-tail dictionary."""
    top = [w.strip() for w in (ASSETS / "words_en.txt").read_text().splitlines() if w.strip()]
    rng = random.Random(20260815)
    pool = []
    for line in (ASSETS / "dicts" / "en_more.txt").read_text(errors="ignore").splitlines():
        w = line.split(" ", 1)[0].strip()
        if w:
            pool.append(w)
    rng.shuffle(pool)

    words = [w for w in top if collapse(w)[1]]
    tail = []
    for w in pool:
        if len(tail) >= LEXICON_TAIL:
            break
        codes, ok = collapse(w)
        if ok and len(codes) >= 2:
            tail.append(w)
    lex_words = words + tail
    counts = [1e6 * (rank + 1) ** -0.85 for rank in range(len(words))] + [120.0] * len(tail)
    priors = priors_from_counts(counts)
    index = {w: i for i, w in enumerate(lex_words)}
    return lex_words, priors, index, len(words), counts


def fit_bigram_table(words, weights):
    """Frequency-weighted, Laplace-smoothed letter bigram probabilities.
    weights[i] scales word i's contribution (sqrt of the frequency estimate:
    common words shape the table more, rare words still contribute). Returns
    (flat log-probabilities row-major prev*26+next, raw probability matrix).
    The flat table is rounded to 6 decimals BEFORE any feature is computed so
    the Kotlin side scores from exactly these numbers."""
    counts = np.zeros((26, 26))
    for w, wt in zip(words, weights):
        letters = [ord(ch) - 97 for ch in w if "a" <= ch <= "z"]
        for a, b in zip(letters[:-1], letters[1:]):
            counts[a, b] += wt
    prob = (counts + BIGRAM_ALPHA) / (counts.sum(axis=1, keepdims=True) + 26.0 * BIGRAM_ALPHA)
    flat = np.round(np.log(prob).ravel(), 6)
    return flat, prob


def fit_trigram_table(words, weights, bigram_prob):
    """Interpolated letter-trigram log-probabilities, flattened row-major
    (prev2*676 + prev1*26 + next). P(c|a,b) is mixed with the back-off P(c|b)
    from the weighted bigram model, so sparse trigram contexts degrade
    gracefully. Rounded to 6 decimals BEFORE any feature is computed."""
    counts = np.zeros((26, 26, 26))
    for w, wt in zip(words, weights):
        letters = [ord(ch) - 97 for ch in w if "a" <= ch <= "z"]
        if len(letters) < 3:
            continue
        np.add.at(counts, (letters[:-2], letters[1:-1], letters[2:]), wt)
    prob_t = (counts + TRIGRAM_ALPHA) / (counts.sum(axis=2, keepdims=True)
                                         + 26.0 * TRIGRAM_ALPHA)
    mix = TRIGRAM_LAMBDA * prob_t + (1.0 - TRIGRAM_LAMBDA) * bigram_prob[None, :, :]
    flat = np.round(np.log(mix).ravel(), 6)
    return flat


def bigram_feature(table, word):
    """Mean log-probability of the word's letter transitions under the frozen
    table. Mirrors SwipeModel.bigramScore: non a-z letters are skipped, a word
    with fewer than two letters scores 0."""
    letters = [ord(ch) - 97 for ch in word if "a" <= ch <= "z"]
    if len(letters) < 2:
        return 0.0
    total = 0.0
    for a, b in zip(letters[:-1], letters[1:]):
        total += table[a * 26 + b]
    return total / (len(letters) - 1)


def trigram_feature(table, word):
    """Mean log-probability of the word's letter trigrams under the frozen
    table. Mirrors SwipeModel.trigramScore: non a-z letters are skipped, a word
    with fewer than three letters scores 0, and the sum is divided by the
    transition count (len - 2)."""
    letters = [ord(ch) - 97 for ch in word if "a" <= ch <= "z"]
    if len(letters) < 3:
        return 0.0
    total = 0.0
    for a, b, c in zip(letters[:-2], letters[1:-1], letters[2:]):
        total += table[a * 676 + b * 26 + c]
    return total / (len(letters) - 2)


class Lexicon:
    def __init__(self, words, priors):
        self.words = words
        self.prior = priors
        self.codes = []
        self.ideal = []
        for w in words:
            codes, ok = collapse(w)
            if not ok or len(codes) < 2:
                codes, ideal = None, 0.0
            else:
                codes = np.array(codes)
                ideal = float(np.hypot(GX[codes[1:]] - GX[codes[:-1]],
                                       GY[codes[1:]] - GY[codes[:-1]]).sum())
            self.codes.append(codes)
            self.ideal.append(ideal)
        # Vector views for the gated stage-1 scan.
        n = len(words)
        self.hasCodes = np.zeros(n, dtype=bool)
        self.fx = np.full(n, np.nan)
        self.fy = np.full(n, np.nan)
        self.lx = np.full(n, np.nan)
        self.ly = np.full(n, np.nan)
        self.idealArr = np.full(n, np.nan)
        self.priorArr = np.asarray(priors, dtype=float)
        for i, codes in enumerate(self.codes):
            if codes is None:
                continue
            self.hasCodes[i] = True
            self.fx[i], self.fy[i] = GX[codes[0]], GY[codes[0]]
            self.lx[i], self.ly[i] = GX[codes[-1]], GY[codes[-1]]
            self.idealArr[i] = self.ideal[i]
        self.byLen = {}
        for i, codes in enumerate(self.codes):
            if codes is not None:
                self.byLen.setdefault(len(codes), []).append(i)


# ------------------------------------------------------------------ path helpers (ports)
def resample(xs, ys, m):
    """Arc-length uniform resample - port of SlideDecoder.resample."""
    xs, ys = np.asarray(xs), np.asarray(ys)
    n = len(xs)
    cum = np.zeros(n)
    cum[1:] = np.hypot(np.diff(xs), np.diff(ys)).cumsum()
    total = cum[-1]
    if n < 2 or total <= 0:
        return np.full(m, xs[0]), np.full(m, ys[0])
    targets = total * np.arange(m) / (m - 1)
    seg_idx = np.searchsorted(cum, targets, side="left")
    seg_idx = np.clip(seg_idx, 1, n - 1)
    a = cum[seg_idx - 1]
    b = cum[seg_idx]
    t = np.where(b > a, np.clip((targets - a) / np.where(b > a, b - a, 1.0), 0, 1), 0.0)
    ox = xs[seg_idx - 1] + (xs[seg_idx] - xs[seg_idx - 1]) * t
    oy = ys[seg_idx - 1] + (ys[seg_idx] - ys[seg_idx - 1]) * t
    return ox, oy


def find_corners(px, py):
    """Port of SlideDecoder.findCorners."""
    m = len(px)
    w = 2
    out = []
    last_angle = 0.0
    for i in range(w, m - w):
        ax, ay = px[i] - px[i - w], py[i] - py[i - w]
        bx, by = px[i + w] - px[i], py[i + w] - py[i]
        la, lb = math.hypot(ax, ay), math.hypot(bx, by)
        if la < 0.12 or lb < 0.12:
            continue
        cos = (ax * bx + ay * by) / (la * lb)
        if cos > 0.5:
            continue
        angle = 1.0 - cos
        if out and i - out[-1] <= w:
            if angle > last_angle:
                out[-1] = i
                last_angle = angle
            continue
        out.append(i)
        last_angle = angle
    return out


def seg_dists(points, vx, vy):
    """Distance from each point to each polyline segment (points x segments)."""
    ax, ay = np.asarray(vx[:-1]), np.asarray(vy[:-1])
    bx, by = np.asarray(vx[1:]), np.asarray(vy[1:])
    abx, aby = bx - ax, by - ay
    ab2 = abx * abx + aby * aby
    ab2 = np.where(ab2 <= 1e-9, 1e-9, ab2)
    dx = points[:, 0][:, None] - ax[None, :]
    dy = points[:, 1][:, None] - ay[None, :]
    t = ((dx * abx[None, :] + dy * aby[None, :]) / ab2[None, :])
    t = np.clip(t, 0.0, 1.0)
    proj_x = ax[None, :] + abx[None, :] * t
    proj_y = ay[None, :] + aby[None, :] * t
    return np.hypot(points[:, 0][:, None] - proj_x, points[:, 1][:, None] - proj_y)


# ------------------------------------------------------------------ trace simulation
def catmull_rom(ctrl):
    ext = [ctrl[0]] + list(ctrl) + [ctrl[-1]]
    pts = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        seg_len = math.hypot(p2[0] - p1[0], p2[1] - p1[1])
        n = max(3, int(seg_len * 16))
        for j in range(n):
            t = j / n
            t2, t3 = t * t, t * t * t
            x = 0.5 * (2 * p1[0] + (-p0[0] + p2[0]) * t +
                       (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 +
                       (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * (2 * p1[1] + (-p0[1] + p2[1]) * t +
                       (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 +
                       (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            pts.append((x, y))
    pts.append((ctrl[-1][0], ctrl[-1][1]))
    return pts


def simulate_trace(codes, rng, doubles=()):
    """Human-like swipe: rounded corners, perpendicular noise, sloppy endpoints,
    plus the error modes real fingers produce - a skipped key, a brief veer
    onto a neighbouring key, a global tilt on top of per-point drift,
    corner-cutting on sharp turns, a loop on a doubled letter, starts that
    drift onto a neighbouring key, and a variable speed profile (fast
    stretches are sloppier than slow ones)."""
    path_codes = list(codes)
    if len(path_codes) >= 4 and rng.random() < 0.12:
        del path_codes[rng.randrange(1, len(path_codes) - 1)]   # finger skipped a key
    centers = [GEO[c] for c in path_codes]

    # Corner-cutting: on sharp turns the finger swings inside the corner and
    # barely touches the key centre (cos < 0.55 is a turn of about 57 degrees).
    if len(centers) >= 3:
        for i in range(1, len(centers) - 1):
            vx1 = centers[i][0] - centers[i - 1][0]
            vy1 = centers[i][1] - centers[i - 1][1]
            vx2 = centers[i + 1][0] - centers[i][0]
            vy2 = centers[i + 1][1] - centers[i][1]
            l1 = math.hypot(vx1, vy1)
            l2 = math.hypot(vx2, vy2)
            if l1 < 1e-6 or l2 < 1e-6:
                continue
            if (vx1 * vx2 + vy1 * vy2) / (l1 * l2) < 0.55 and rng.random() < 0.5:
                k = rng.uniform(0.25, 0.55)
                mx = 0.5 * (centers[i - 1][0] + centers[i + 1][0])
                my = 0.5 * (centers[i - 1][1] + centers[i + 1][1])
                centers[i] = (centers[i][0] * (1 - k) + mx * k,
                              centers[i][1] * (1 - k) + my * k)

    if len(path_codes) >= 3 and rng.random() < 0.15:
        i = rng.randrange(1, len(path_codes) - 1)               # veer towards a neighbour
        nb = GEO[NEIGHBOURS[path_codes[i]][0]]
        k = rng.uniform(0.35, 0.7)
        centers[i] = (centers[i][0] * (1 - k) + nb[0] * k,
                      centers[i][1] * (1 - k) + nb[1] * k)

    # Doubled letters: real fingers loop around the key instead of touching it
    # once (drawn "letter" circles on the t).
    doubles = [d for d in doubles if 0 <= d < len(centers)]
    if doubles and len(centers) >= 2 and rng.random() < 0.55:
        di = doubles[rng.randrange(len(doubles))]
        c0 = centers[di]
        r = rng.uniform(0.22, 0.42)
        a1 = rng.uniform(0.0, 2.0 * math.pi)
        a2 = a1 + rng.uniform(2.2, 4.0)
        centers[di:di + 1] = [c0,
                              (c0[0] + r * math.cos(a1), c0[1] + r * math.sin(a1)),
                              (c0[0] + r * math.cos(a2), c0[1] + r * math.sin(a2)),
                              c0]

    start_mode = rng.random()
    sx0, sy0 = centers[0]
    if start_mode < 0.10:
        # start drifted onto the edge of a neighbouring key
        nb = GEO[NEIGHBOURS[path_codes[0]][0]]
        k = rng.uniform(0.3, 0.6)
        sx, sy = sx0 * (1 - k) + nb[0] * k, sy0 * (1 - k) + nb[1] * k
        sx += rng.uniform(-0.1, 0.1)
        sy += rng.uniform(-0.08, 0.08)
    else:
        sx = sx0 + rng.uniform(-0.22, 0.22)
        sy = sy0 + rng.uniform(-0.16, 0.16)
    ex = centers[-1][0] + rng.uniform(-0.28, 0.28)
    ey = centers[-1][1] + rng.uniform(-0.22, 0.22)
    if rng.random() < 0.10:      # occasionally a very sloppy finish
        ex += rng.uniform(-0.2, 0.2)
        ey += rng.uniform(-0.16, 0.16)
    ctrl = [(sx, sy), centers[0]] + centers[1:-1] + [centers[-1], (ex, ey)]
    dense = catmull_rom(ctrl)
    length = sum(math.hypot(dense[i][0] - dense[i - 1][0], dense[i][1] - dense[i - 1][1])
                 for i in range(1, len(dense)))
    m_raw = max(8, min(400, int(length / 0.07)))
    px, py = resample([p[0] for p in dense], [p[1] for p in dense], m_raw)

    roll = rng.random()
    if roll < 0.15:              # occasionally a very clean swipe
        sigma = rng.uniform(0.015, 0.04)
    elif roll < 0.37:            # occasional sloppy swipe
        sigma = rng.uniform(0.065, 0.24)
    else:
        sigma = rng.uniform(0.035, 0.15)
    drift = 0.0
    tilt_x = rng.uniform(-0.05, 0.05)     # systematic tilt of the whole trace
    tilt_y = rng.uniform(-0.04, 0.04)
    # Variable speed: a smooth random profile over the trace; fast stretches
    # carry more noise than slow ones (fingers brake into corners).
    f1 = rng.uniform(1.2, 3.0)
    f2 = rng.uniform(3.0, 6.0)
    ph1 = rng.uniform(0.0, 2.0 * math.pi)
    ph2 = rng.uniform(0.0, 2.0 * math.pi)
    amp1 = rng.uniform(0.35, 0.65)
    out_x = np.empty(m_raw)
    out_y = np.empty(m_raw)
    for j in range(m_raw):
        i0, i1 = max(0, j - 1), min(m_raw - 1, j + 1)
        dx, dy = px[i1] - px[i0], py[i1] - py[i0]
        d = math.hypot(dx, dy)
        if d > 1e-9:
            nx_, ny_ = -dy / d, dx / d
        else:
            nx_, ny_ = 0.0, 0.0
        t = j / max(1, m_raw - 1)
        speed = 0.5 + amp1 * math.sin(2.0 * math.pi * f1 * t + ph1) \
                     + (1.0 - amp1) * math.sin(2.0 * math.pi * f2 * t + ph2)
        speed = min(1.0, max(0.0, 0.5 * (speed + 0.5)))   # smooth to [0, 1]
        drift = 0.92 * drift + rng.gauss(0.0, 0.014)
        off = rng.gauss(0.0, sigma * (0.55 + 0.9 * speed)) + drift
        edge = 1.6 if (j < 3 or j > m_raw - 4) else 1.0
        out_x[j] = px[j] + nx_ * off * edge + tilt_x * t
        out_y[j] = py[j] + ny_ * off * edge + tilt_y * t
    return out_x, out_y


# ------------------------------------------------------------------ decoder (ports)
class Trace:
    """Precomputed per-swipe state, mirrors SlideDecoder.Run."""

    def __init__(self, xs, ys):
        length = float(np.hypot(np.diff(xs), np.diff(ys)).sum())
        self.length = length
        self.m = int(min(M_MAX, max(M_MIN, round(length / RES))))
        self.px, self.py = resample(xs, ys, self.m)
        self.shape_x, self.shape_y = resample(xs, ys, SHAPE_N)
        self.corners = find_corners(self.px, self.py)
        self.points = np.stack([self.px, self.py], axis=1)
        # Squared distances from every resampled point to every key centre,
        # shared by the vectorised stage-1 DP (m x 26).
        self.d2 = ((self.px[:, None] - GX[None, :]) ** 2 +
                   (self.py[:, None] - GY[None, :]) ** 2)
        # visited-letter string: nearest key centre within SWIPE_KEY_R
        d = np.hypot(self.points[:, 0][:, None] - GX[None, :],
                     self.points[:, 1][:, None] - GY[None, :])
        nearest = d.argmin(axis=1)
        near_ok = d.min(axis=1) <= SWIPE_KEY_R
        letters = []
        last = -1
        for j in range(self.m):
            if near_ok[j] and nearest[j] != last:
                letters.append(chr(97 + int(nearest[j])))
                last = int(nearest[j])
        self.swipe_str = "".join(letters)

    def min_seg_dist(self, vx, vy):
        """Per-point distance to the drawn polyline."""
        if len(vx) == 1:
            return np.hypot(self.points[:, 0] - vx[0], self.points[:, 1] - vy[0])
        return seg_dists(self.points, vx, vy).min(axis=1)


def batch_align(trace, lex, cand):
    """Stage-1 DP alignment, vectorised across candidates. Identical arithmetic
    to stage1_align per candidate (same accumulation order, float64)."""
    out = np.empty(len(cand))
    if len(cand) == 0:
        return out
    s2 = 2.0 * SIGMA * SIGMA
    e2 = 2.0 * END_SIGMA * END_SIGMA
    by_len = {}
    for pos, i in enumerate(cand):
        by_len.setdefault(len(lex.codes[i]), []).append(pos)
    for L, positions in by_len.items():
        first = np.array([lex.codes[cand[p]][0] for p in positions])
        last = np.array([lex.codes[cand[p]][-1] for p in positions])
        align = trace.d2[0, first] / e2
        if L > 2:
            dp = np.tile(align[:, None], (1, trace.m))
            for k in range(1, L - 1):
                key = np.array([lex.codes[cand[p]][k] for p in positions])
                dists = trace.d2[:, key].T / s2            # (C, m)
                dp = np.minimum.accumulate(dp, axis=1) + dists
            align = dp.min(axis=1)
        out[positions] = align + trace.d2[-1, last] / e2
    return out


def stage1_align(trace, codes):
    """Port of stage 1's DP alignment for a single candidate (reference
    implementation; the batched fast path must agree with this)."""
    m = trace.m
    s2 = 2.0 * SIGMA * SIGMA
    e2 = 2.0 * END_SIGMA * END_SIGMA
    fx, fy = GX[codes[0]], GY[codes[0]]
    lx, ly = GX[codes[-1]], GY[codes[-1]]
    dp = np.full(m, np.inf)
    dp[0] = ((fx - trace.px[0]) ** 2 + (fy - trace.py[0]) ** 2) / e2
    for k in range(1, len(codes) - 1):
        dists = ((GX[codes[k]] - trace.px) ** 2 + (GY[codes[k]] - trace.py) ** 2) / s2
        dp = np.minimum.accumulate(dp) + dists
    align = float(dp.min()) + ((lx - trace.px[-1]) ** 2 + (ly - trace.py[-1]) ** 2) / e2
    ideal = float(np.hypot(GX[codes[1:]] - GX[codes[:-1]],
                           GY[codes[1:]] - GY[codes[:-1]]).sum())
    ratio = math.log((trace.length + 0.4) / (ideal + 0.4))
    return align, ratio


def fast_candidates(trace, lex, true_idx):
    """Stage 1 in Python, vectorised: gated survivors ranked by s1, capped plus
    the true word."""
    ok = lex.hasCodes.copy()
    ok &= np.hypot(lex.fx - trace.px[0], lex.fy - trace.py[0]) <= START_R
    ok &= np.hypot(lex.lx - trace.px[-1], lex.ly - trace.py[-1]) <= END_R
    with np.errstate(invalid="ignore"):
        ratio = np.log((trace.length + 0.4) / (lex.idealArr + 0.4))
    ok &= np.isfinite(ratio) & (np.abs(ratio) <= RMAX)
    cand = np.nonzero(ok)[0]
    if cand.size == 0:
        return [true_idx] if true_idx is not None else []
    aligns = batch_align(trace, lex, cand.tolist())
    s1 = aligns + W_LEN * ratio[cand] ** 2 + W_LM * (1.0 - lex.priorArr[cand])
    order = np.argsort(s1, kind="stable")
    keep = [int(cand[i]) for i in order[:STAGE1_KEEP_EVAL]]
    if true_idx is not None and true_idx not in keep:
        keep.append(true_idx)
    return keep


def edit_counts(trace, codes):
    """Port of the typed-error decomposition. Returns (om, ins, tr, near, gen)."""
    word_str = "".join(chr(97 + int(c)) for c in codes)
    swipe = trace.swipe_str
    om = ins = tr = near = gen = 0
    wi = si = 0
    while wi < len(word_str) and si < len(swipe):
        wc, sc = word_str[wi], swipe[si]
        if wc == sc:
            wi += 1
            si += 1
            continue
        if (wi + 1 < len(word_str) and si + 1 < len(swipe) and
                word_str[wi + 1] == sc and wc == swipe[si + 1]):
            tr += 1
            wi += 2
            si += 2
        elif si + 1 < len(swipe) and swipe[si + 1] == wc:
            ins += 1
            si += 1
        elif wi + 1 < len(word_str) and word_str[wi + 1] == sc:
            om += 1
            wi += 1
        else:
            d = PAIR[ord(wc) - 97, ord(sc) - 97] if ("a" <= wc <= "z" and "a" <= sc <= "z") else np.nan
            if not np.isnan(d) and d <= 1.5:
                near += 1
            else:
                gen += 1
            wi += 1
            si += 1
    om += max(0, len(word_str) - wi)
    ins += max(0, len(swipe) - si)
    return om, ins, tr, near, gen


def score_candidate(trace, codes, prior, best_raw, align, ratio, word, bigrams,
                    trigrams):
    """Stage-2 features (trained-model vector) + the hand-rule score pieces."""
    vx = GX[codes]
    vy = GY[codes]
    raw = float(trace.min_seg_dist(vx, vy).mean())

    wx, wy = resample(vx, vy, SHAPE_N)
    shape = float(np.hypot(wx - trace.shape_x, wy - trace.shape_y).mean())

    seg_min = trace.min_seg_dist(vx, vy)
    over = np.maximum(0.0, seg_min - ADH_TOL)
    adh = float((over * over).mean())

    turn = 0.0
    if trace.corners:
        cpts = trace.points[trace.corners]
        dists = np.hypot(cpts[:, 0][:, None] - vx[None, :],
                         cpts[:, 1][:, None] - vy[None, :]).min(axis=1)
        turn = float(np.maximum(0.0, dists - CORNER_FREE).sum())

    cdist = min(1.5, max(0.0, raw - best_raw))
    vowel_dev = abs(VOWELS[codes].sum() / len(codes) - 0.4)
    om, ins, tr, near, gen = edit_counts(trace, codes)
    align_len = max(len(trace.swipe_str), len(codes), 1)
    edit_norm = (om + ins + tr + near + gen) / align_len
    startd = math.hypot(trace.px[0] - vx[0], trace.py[0] - vy[0])
    endd = math.hypot(trace.px[-1] - vx[-1], trace.py[-1] - vy[-1])
    bg = bigram_feature(bigrams, word)
    tg = trigram_feature(trigrams, word)

    feats = np.array([shape, adh, turn, cdist, vowel_dev, edit_norm,
                      ratio, prior, float(len(codes)),
                      shape * shape, adh * adh, edit_norm * len(codes),
                      startd, endd, bg, float(align), tg])

    hand_edit = min(2.0, (W_OMISSION * om + W_INSERTION * ins + W_TRANSPOSITION * tr +
                          W_NEARBY_SUB * near + W_GENERAL_SUB * gen) / align_len)
    char_num = W_CHAR_NUM * (3 - len(codes)) if len(codes) < 3 else 0.0
    hand = align + W_LEN * ratio * ratio + W_LM * (1.0 - prior) + \
        W_SHAPE * shape + W_ADH * adh + W_CORNER * turn + W_C_DIST * cdist + \
        W_VOWEL * vowel_dev + hand_edit + char_num
    return feats, hand


# ------------------------------------------------------------------ dataset
def build_dataset(lex, word_indices, seeds, bigrams, trigrams, seed_base=0,
                  progress=False, tag=""):
    """Simulate traces for the given words; each trace yields one group of
    scored candidates. Seed keys are word_index*1000 + seed_base + seed.
    Returns (groups, group_words) - the word index behind each group, used by
    hard-negative mining."""
    groups = []
    group_words = []
    done = 0
    for wi in word_indices:
        codes = lex.codes[wi]
        word = lex.words[wi]
        dbl = doubled_positions(word)
        for seed in range(seeds):
            xs, ys = simulate_trace(list(map(int, codes)),
                                    random.Random(wi * 1000 + seed_base + seed), dbl)
            trace = Trace(xs, ys)
            keep = fast_candidates(trace, lex, wi)
            if not keep:
                continue
            aligns = batch_align(trace, lex, keep)
            raws = [float(trace.min_seg_dist(GX[lex.codes[i]], GY[lex.codes[i]]).mean())
                    for i in keep]
            best_raw = min(raws)
            rows = []
            for pos, i in enumerate(keep):
                ratio = math.log((trace.length + 0.4) / (lex.ideal[i] + 0.4))
                feats, hand = score_candidate(trace, lex.codes[i], float(lex.prior[i]),
                                              best_raw, float(aligns[pos]), ratio,
                                              word, bigrams, trigrams)
                rows.append((feats, hand, 1 if i == wi else 0))
            groups.append(rows)
            group_words.append(wi)
        done += 1
        if progress and done % 500 == 0:
            print(f"  {tag}{done}/{len(word_indices)} words simulated", flush=True)
    return groups, group_words


class Matrix:
    """Groups flattened into contiguous arrays for vectorised training."""

    def __init__(self, groups):
        sizes = [len(g) for g in groups]
        self.starts = np.cumsum([0] + sizes)[:-1].astype(np.int64)
        self.sizes = np.array(sizes, dtype=np.int64)
        self.n_groups = len(groups)
        self.F = np.zeros((sum(sizes), NF))
        self.hand = np.zeros(sum(sizes))
        self.y = np.zeros(sum(sizes))
        r = 0
        for g in groups:
            for feats, hand, y in g:
                self.F[r] = feats
                self.hand[r] = hand
                self.y[r] = y
                r += 1
        self.Fz = None          # standardised copy, filled by standardise()

    def standardise(self, mean, std):
        # Clip to +/-CLIP_Z deviations: matches the clamp SwipeModel.penalty
        # applies at runtime, and stops heavy-tailed features from saturating
        # the tanh hidden layer during training.
        self.Fz = np.clip((self.F - mean) / std, -CLIP_Z, CLIP_Z)


def group_argmin(scores, starts, sizes):
    """First-minimum index per group - matches np.argmin on each group view."""
    out = np.empty(len(sizes), dtype=np.int64)
    for g in range(len(sizes)):
        s, n = starts[g], sizes[g]
        out[g] = s + int(np.argmin(scores[s:s + n]))
    return out


def top1_scores(mat, scores):
    pred = group_argmin(scores, mat.starts, mat.sizes)
    return float((mat.y[pred] == 1).sum() / max(1, mat.n_groups))


def topk_scores(mat, scores, k=3):
    correct = 0
    for g in range(mat.n_groups):
        s, n = mat.starts[g], mat.sizes[g]
        order = np.argsort(scores[s:s + n], kind="stable")[:k]
        if (mat.y[s + order] == 1).any():
            correct += 1
    return float(correct / max(1, mat.n_groups))


def group_softmax(logits, starts, sizes):
    mx = np.maximum.reduceat(logits, starts)
    e = np.exp(logits - np.repeat(mx, sizes))
    return e / np.repeat(np.add.reduceat(e, starts), sizes)


def fit_linear(mat, iters=450, lr=0.06, l2=2e-4):
    """Linear logit ranker, full-batch Adam. Uses raw (unstandardised) features:
    a linear model is scale-invariant per feature."""
    F, y, starts, sizes = mat.F, mat.y, mat.starts, mat.sizes
    G = float(mat.n_groups)
    w = np.zeros(NF)
    b = 0.0
    m_w = np.zeros(NF)
    v_w = np.zeros(NF)
    m_b = v_b = 0.0
    for t in range(1, iters + 1):
        logits = F @ w + b
        p = group_softmax(logits, starts, sizes)
        gw = F.T @ (p - y) / G + l2 * w
        gb = float((p - y).sum()) / G
        m_w = 0.9 * m_w + 0.1 * gw
        v_w = 0.999 * v_w + 0.001 * gw * gw
        m_b = 0.9 * m_b + 0.1 * gb
        v_b = 0.999 * v_b + 0.001 * gb * gb
        w -= lr * (m_w / (1 - 0.9 ** t)) / (np.sqrt(v_w / (1 - 0.999 ** t)) + 1e-8)
        b -= lr * (m_b / (1 - 0.999 ** t)) / (math.sqrt(v_b / (1 - 0.999 ** t)) + 1e-8)
    return w, b


def forward_mlp(params, Fz):
    """Logits for a batch of feature rows: NF -> tanh(hidden) -> 1."""
    w1, b1, w2, b2 = params
    return np.tanh(Fz @ w1.T + b1) @ w2 + b2


def fit_mlp(train, hold, hidden=24, iters=900, lr=0.02, l2=3e-4, seed=4242,
            eval_every=25):
    """One-hidden-layer ranker over STANDARDISED features, trained with the
    same per-group cross-entropy as the linear model. Learning rate steps down
    as training proceeds; keeps the best hold-out top-1 seen (early stopping)."""
    rng = np.random.RandomState(seed)
    # Label smoothing over each trace's candidate set: p_true = 1-e+e/n for the
    # gold word, e/n for the rest (n = candidates in the group). Keeps logits
    # calibrated; the evaluation matrices keep their raw 0/1 labels.
    inv_n = np.repeat(1.0 / train.sizes, train.sizes)
    Fz = train.Fz
    y = train.y + EPS * (inv_n - train.y)
    starts, sizes = train.starts, train.sizes
    G = float(train.n_groups)
    w1 = rng.normal(0.0, 0.15, (hidden, NF))
    b1 = np.zeros(hidden)
    w2 = rng.normal(0.0, 0.15, hidden)
    b2 = 0.0
    m = [np.zeros_like(w1), np.zeros_like(b1), np.zeros_like(w2), 0.0]
    v = [np.zeros_like(w1), np.zeros_like(b1), np.zeros_like(w2), 0.0]
    best_acc, best = -1.0, None
    for t in range(1, iters + 1):
        # step decay: the lr is multiplied by 0.4 roughly every 30% of training
        lr_t = lr * (0.4 ** (t // max(1, int(iters * 0.3))))
        H = np.tanh(Fz @ w1.T + b1)
        logits = H @ w2 + b2
        p = group_softmax(logits, starts, sizes)
        dz = (p - y) / G
        gw2 = H.T @ dz + l2 * w2
        gb2 = float(dz.sum())
        dh = dz[:, None] * (1.0 - H * H) * w2[None, :]
        gw1 = dh.T @ Fz + l2 * w1
        gb1 = dh.sum(axis=0)
        g = [gw1, gb1, gw2, gb2]
        for i in range(4):
            m[i] = 0.9 * m[i] + 0.1 * g[i]
            v[i] = 0.999 * v[i] + 0.001 * g[i] * g[i]
            mh = m[i] / (1 - 0.9 ** t)
            vh = v[i] / (1 - 0.999 ** t)
            upd = lr_t * mh / (np.sqrt(vh) + 1e-8)
            if i == 0:
                w1 -= upd
            elif i == 1:
                b1 -= upd
            elif i == 2:
                w2 -= upd
            else:
                b2 -= upd
        if hold is not None and (t % eval_every == 0 or t == iters):
            # rank the hold-out by the pure model score (lower logit = worse
            # candidate), NOT by hand - blend * logit: a wrong sign here silently
            # keeps the worst checkpoint (and a constant hand score would make
            # early stopping a no-op).
            acc = top1_scores(hold, -forward_mlp((w1, b1, w2, float(b2)), hold.Fz))
            if acc > best_acc:
                best_acc = acc
                best = (w1.copy(), b1.copy(), w2.copy(), float(b2))
    return (best if best is not None else (w1, b1, w2, float(b2))), best_acc


def _arg_value(flag):
    """--flag value from sys.argv, or None."""
    argv = sys.argv
    for i, a in enumerate(argv):
        if a == flag and i + 1 < len(argv):
            return argv[i + 1]
        if a.startswith(flag + "="):
            return a.split("=", 1)[1]
    return None


def save_cache(path, train_mat, hold_mat, n_train_words, n_mined):
    np.savez_compressed(path,
                        F_tr=train_mat.F, hand_tr=train_mat.hand, y_tr=train_mat.y,
                        starts_tr=train_mat.starts, sizes_tr=train_mat.sizes,
                        F_ho=hold_mat.F, hand_ho=hold_mat.hand, y_ho=hold_mat.y,
                        starts_ho=hold_mat.starts, sizes_ho=hold_mat.sizes,
                        n_train_words=np.array([n_train_words]),
                        n_mined=np.array([n_mined]))


def load_cache(path):
    z = np.load(path)
    tr = Matrix.__new__(Matrix)
    tr.F = z["F_tr"]
    tr.hand = z["hand_tr"]
    tr.y = z["y_tr"]
    tr.starts = z["starts_tr"]
    tr.sizes = z["sizes_tr"]
    tr.n_groups = len(tr.sizes)
    tr.Fz = None
    ho = Matrix.__new__(Matrix)
    ho.F = z["F_ho"]
    ho.hand = z["hand_ho"]
    ho.y = z["y_ho"]
    ho.starts = z["starts_ho"]
    ho.sizes = z["sizes_ho"]
    ho.n_groups = len(ho.sizes)
    ho.Fz = None
    return tr, ho, int(z["n_train_words"][0]), int(z["n_mined"][0])


# ------------------------------------------------------------------ main
def main():
    lex_words, priors, index, n_top, counts = load_lexicon()
    lex = Lexicon(lex_words, priors)
    lm_weights = np.asarray(counts, dtype=float) ** LM_WEIGHT_POWER
    bigrams, bigram_prob = fit_bigram_table(lex_words, lm_weights)
    trigrams = fit_trigram_table(lex_words, lm_weights, bigram_prob)
    top_indices = [index[w] for w in lex_words[:n_top] if collapse(w)[1]]
    print(f"lexicon: {len(lex_words)} words ({n_top} top)", flush=True)

    rng = random.Random(31337)
    shuffled = list(top_indices)
    rng.shuffle(shuffled)
    cut = int(len(shuffled) * 0.8)
    train_top, hold_top = shuffled[:cut], shuffled[cut:]

    # A slice of the long tail as targets too: short tail words are the hardest
    # confusables, so the ranker learns the cases the head words never show.
    tail_pool = [i for i in range(n_top, len(lex.words))
                 if lex.codes[i] is not None and 3 <= len(lex.codes[i]) <= 8]
    rng.shuffle(tail_pool)
    tail_pool = tail_pool[:TAIL_TARGETS]
    tail_cut = int(len(tail_pool) * 0.8)
    train_tail, hold_tail = tail_pool[:tail_cut], tail_pool[tail_cut:]
    train_idx = train_top + train_tail
    hold_idx = hold_top + hold_tail
    print(f"targets: train={len(train_idx)} holdout={len(hold_idx)}", flush=True)

    # Training traces get more seeds than the baseline run; the HOLD-OUT keeps
    # the baseline's 20/4 so its groups (and scores) stay comparable run over
    # run - the only change there is the feature columns themselves.
    seeds_top, seeds_tail = (2, 1) if SMOKE else (24, 6)
    hold_seeds_top, hold_seeds_tail = (2, 1) if SMOKE else (20, 4)
    cache_path = _arg_value("--cache")
    word_ids = []
    n_train_words = len(train_idx)
    if cache_path and Path(cache_path).exists():
        print(f"loading dataset cache {cache_path} ...", flush=True)
        train_mat, hold_mat, n_train_words, n_mined = load_cache(cache_path)
        print(f"groups: train={train_mat.n_groups} holdout={hold_mat.n_groups}", flush=True)
    else:
        print("simulating traces ...", flush=True)
        top_groups, top_words = build_dataset(lex, train_top, seeds_top, bigrams, trigrams,
                                              progress=True, tag="top ")
        tail_groups, tail_words = build_dataset(lex, train_tail, seeds_tail, bigrams, trigrams,
                                                tag="tail ")
        train_groups = top_groups + tail_groups
        train_group_words = top_words + tail_words
        hold_groups, _ = build_dataset(lex, hold_top, hold_seeds_top, bigrams, trigrams,
                                       tag="hold top ")
        hb_groups, _ = build_dataset(lex, hold_tail, hold_seeds_tail, bigrams, trigrams,
                                     tag="hold tail ")
        hold_groups += hb_groups
        print(f"groups: train={len(train_groups)} holdout={len(hold_groups)}", flush=True)

        # ── Hard-negative mining (two rounds): words the fitted ranker still
        #    mis-ranks get extra traces (training words only - the hold-out
        #    stays untouched). Round 2 re-fits on the round-1 enriched data, so
        #    the second batch of hard words is found with a better ranker in
        #    the loop.
        if not SMOKE:
            mined_before = set()
            for round_no, cap, seed_base in ((1, 1500, 60), (2, 800, 120)):
                print(f"mining hard words (round {round_no}) ...", flush=True)
                mine_mat = Matrix(train_groups)
                w0, b0 = fit_linear(mine_mat, iters=300)
                scores0 = mine_mat.hand - 8.0 * (mine_mat.F @ w0 + b0)
                pred0 = group_argmin(scores0, mine_mat.starts, mine_mat.sizes)
                seen = set(mined_before)
                hard_ids = []
                for g_i, w_idx in enumerate(train_group_words):
                    if mine_mat.y[pred0[g_i]] != 1 and w_idx not in seen:
                        seen.add(w_idx)
                        hard_ids.append(w_idx)
                hard_ids = hard_ids[:cap]
                mined_before.update(hard_ids)
                word_ids.extend(hard_ids)
                print(f"  hard words: {len(hard_ids)}", flush=True)
                if hard_ids:
                    extra, extra_words = build_dataset(lex, hard_ids, 2, bigrams, trigrams,
                                                       seed_base=seed_base, tag="mine ")
                    print(f"  mined traces: {len(extra)}", flush=True)
                    train_groups += extra
                    train_group_words += extra_words

        train_mat = Matrix(train_groups)
        hold_mat = Matrix(hold_groups)
        n_train_words = len(train_idx) + len(word_ids)
        if cache_path:
            # Store the POST-mining word count so a cached re-run reports the
            # same meta as the run that built the cache.
            save_cache(cache_path, train_mat, hold_mat, n_train_words, len(word_ids))
            print(f"saved dataset cache {cache_path}", flush=True)

    # Standardisation stats from TRAINING rows only, rounded before use so the
    # Kotlin side divides by exactly these numbers.
    mean = np.round(train_mat.F.mean(axis=0), 6)
    std = np.round(train_mat.F.std(axis=0), 6)
    std = np.maximum(std, 1e-6)
    train_mat.standardise(mean, std)
    hold_mat.standardise(mean, std)

    base_tr = top1_scores(train_mat, train_mat.hand)
    base_ho = top1_scores(hold_mat, hold_mat.hand)
    print(f"hand-rule top1: train={base_tr:.4f} holdout={base_ho:.4f}")

    w, b = fit_linear(train_mat)
    print(f"linear weights={np.round(w, 3).tolist()} bias={b:.4f}")

    def lin_scores(mat, blend):
        return mat.hand - blend * (mat.F @ w + b)

    print("training neural rerankers (hyper-parameter sweep) ...", flush=True)
    if SMOKE:
        mlp_configs = [(16, 300, 0.02)]
    else:
        mlp_configs = [
            (24, 1400, 0.02),
            (48, 1400, 0.02),
            (64, 1400, 0.02),
            (48, 1800, 0.012),
            (64, 1800, 0.012),
        ]
    params, mlp_hold_raw, mlp_cfg = None, -1.0, mlp_configs[0]
    for hidden, iters, lr in mlp_configs:
        cand, acc = fit_mlp(train_mat, hold_mat, hidden=hidden, iters=iters, lr=lr)
        print(f"  mlp hidden={hidden} iters={iters} lr={lr}: holdout top1={acc:.4f}", flush=True)
        if acc > mlp_hold_raw:
            params, mlp_hold_raw, mlp_cfg = cand, acc, (hidden, iters, lr)
    if params is None:                      # defensive; the first config always runs
        params, _ = fit_mlp(train_mat, hold_mat)

    def mlp_scores(mat, blend):
        return mat.hand - blend * forward_mlp(params, mat.Fz)

    blends = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 26.0,
              34.0, 44.0, 55.0, 66.0, 80.0, 100.0, 115.0, 130.0, 145.0, 160.0,
              180.0, 200.0, 220.0, 260.0]
    lin_best_blend, lin_best = 0.0, base_ho
    for bl in blends:
        acc = top1_scores(hold_mat, lin_scores(hold_mat, bl))
        print(f"  linear blend={bl:>4}: holdout top1={acc:.4f}")
        if acc > lin_best:
            lin_best_blend, lin_best = bl, acc
    print(f"linear best: blend={lin_best_blend} top1={lin_best:.4f}")

    mlp_best_blend, mlp_best = 0.0, base_ho
    for bl in blends:
        acc = top1_scores(hold_mat, mlp_scores(hold_mat, bl))
        print(f"  neural blend={bl:>4}: holdout top1={acc:.4f}")
        if acc > mlp_best:
            mlp_best_blend, mlp_best = bl, acc
    print(f"neural best: blend={mlp_best_blend} top1={mlp_best:.4f}")

    if mlp_best > lin_best:
        best_blend, best_acc, kind = mlp_best_blend, mlp_best, 3
        print(f"winner: standardised neural (v3) {best_acc:.4f}")
        model = {
            "version": 3,
            "features": FEATURE_NAMES,
            "hidden": {"w": [round(float(x), 6) for x in params[0].ravel()],
                       "b": [round(float(x), 6) for x in params[1]],
                       "units": int(params[1].size)},
            "output": {"w": [round(float(x), 6) for x in params[2]],
                       "b": round(float(params[3]), 6)},
            "norm": {"mean": [round(float(x), 6) for x in mean],
                     "std": [round(float(x), 6) for x in std]},
            "blend": best_blend,
        }
        scores_final = mlp_scores(hold_mat, best_blend)
    else:
        best_blend, best_acc, kind = lin_best_blend, lin_best, 1
        print(f"winner: linear (v1) {best_acc:.4f}")
        model = {
            "version": 1,
            "features": FEATURE_NAMES,
            "weights": [round(float(x), 5) for x in w],
            "bias": round(float(b), 5),
            "blend": best_blend,
        }
        scores_final = lin_scores(hold_mat, best_blend)

    # The bigram and trigram tables ship with every model class: the decoder
    # feeds the LM features from these exact frozen numbers.
    model["bigrams"] = [round(float(x), 6) for x in bigrams]
    model["trigrams"] = [round(float(x), 6) for x in trigrams]

    final_top1 = top1_scores(hold_mat, scores_final)
    final_top3 = topk_scores(hold_mat, scores_final, k=3)
    model["meta"] = {
        "traces": train_mat.n_groups + hold_mat.n_groups,
        "trainingWords": n_train_words,
        "candidatesPerTrace": STAGE1_KEEP_EVAL,
        "holdoutTop1Baseline": round(base_ho, 4),
        "holdoutTop1Linear": round(lin_best, 4),
        "holdoutTop1Neural": round(mlp_best, 4),
        "holdoutTop1Final": round(best_acc, 4),
        "holdoutTop3Final": round(final_top3, 4),
        "simulator": "v4: corner-cutting, double-letter loops, variable-speed "
                     "noise, neighbour-key starts, two mining rounds",
        "bigramSmoothing": BIGRAM_ALPHA,
        "trigramLambda": TRIGRAM_LAMBDA,
        "trigramAlpha": TRIGRAM_ALPHA,
        "clipZ": CLIP_Z,
        "labelSmoothing": EPS,
        "mlp": "hidden={}, iters={}, lr={}".format(*mlp_cfg),
    }
    out = ASSETS / "swipe_model.json"
    out.write_text(json.dumps(model, indent=1) + "\n")
    print(f"wrote {out}  v{kind} blend={best_blend}  holdout {base_ho:.4f} -> {best_acc:.4f} "
          f"(top3 {final_top3:.4f})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
