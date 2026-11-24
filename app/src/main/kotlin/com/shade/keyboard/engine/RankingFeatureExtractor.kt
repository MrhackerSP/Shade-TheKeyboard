package com.shade.keyboard.engine

/**
 * Builds the 83-float input vector for `ranking/ranker_en.tflite` in the model's
 * REAL input contract. The asset is a byte-level re-serialization of the bundled
 * neural suggestion model; its flatbuffer was decoded op-by-op (all 160 weight
 * tensors extracted, an exact numpy reimplementation built as oracle, LiteRT
 * outputs reproduced) to pin the contract below. Every claim here is
 * numerically verified against the on-device file.
 *
 * Contract (positions 0..82):
 *
 * | positions  | meaning |
 * |------------|---------|
 * | `x[0:51]`  | 51 scalar decoder features, z-scored in-model per position `v' = (v − mean)/std`. Shade cannot reproduce the source model's internal decoder state, so every z-scored position is filled with the documented **population mean** — in-model z-score becomes 0, the statistically neutral value. Feeding raw zeros instead parks the network at −4σ off-center (positions 33/34) and it collapses into its all-zero no-op zone; mean-imputation keeps it live (verified: outputs 0.35–0.76 varying with input). |
 * | `x[46]`,`x[47]` | user-history n-gram counts, **raw** — the model applies `log(v+1)` itself, so inputs must be ≥ 0. Wired to Shade's persistent user unigram count (`UserWordMemory.count`) and session bigram count (`SessionBigramBoost.bigramCount`), capped at 100 (`log(101) ≈ 4.6` covers the meaningful range). |
 * | `x[51]`    | row id 0..14 into a 15×8 embedding (small categorical, exact meaning unknown) → 0. |
 * | `x[52:67]` | **char sequence A** = candidate text, raw ASCII codes as floats, `0` = padding, 15 slots. Codes are restricted to the char-embedding's 123 rows (0..122 = ASCII 0..'z'); anything else (accents, Cyrillic, emoji) is dropped rather than indexed out of the table. |
 * | `x[67:82]` | **char sequence B** = the typed word, same encoding. Feeding the real typed word keeps the model in its two-sequence regime (its designed mode); with zero scalars that regime scored 0, but with mean-imputed scalars it stays live. |
 * | `x[82]`    | top-candidate type code 0..33 (the source model's TopCandidateType enum; only **13** is behaviorally verified — the model suppresses it by design). Shade word candidates map to 0. |
 *
 * Model output semantics (confirmed numerically against LiteRT and an exact
 * numpy reimplementation):
 * ```
 * p   = sigmoid(MLP(...))
 * s   = 0.5·[type==13] + 0.1·[type==13 & x35==0] + 0.2·[type!=13 & x35==0]
 * cap = −1 if (x[9] > 20) or (x[10] > 20) else +1     // never fires with our fills
 * out = min(max(0, p − s), cap)                        // in [−1, 1]
 * ```
 *
 * Verified with this wiring (exact-float numpy oracle on the carved d3 model
 * + LiteRT on the on-device asset, agreeing within the documented ≤ 3e-2
 * hybrid-int8 residual): plausible English words score 0.35–0.76 and gibberish
 * ≤ 0.51, both rising with user-history counts (capped at 100); type 13 is
 * suppressed via its −0.5/−0.6 s-offset; non-ASCII text degrades gracefully to
 * the scalar path; the veto cap never fires (x[9]/x[10] sit at population
 * means). For contrast, the previous self-invented wiring scored exactly 0.0
 * on the same probes.
 */
object RankingFeatureExtractor {
    const val FEATURE_SIZE = 83

    const val CHAR_A_START = 52
    const val CHAR_B_START = 67
    const val CHAR_SLOTS = 15

    /** The char-embedding table has 123 rows = char codes 0..122 (ASCII 0..'z'). */
    const val MAX_CHAR_CODE = 122

    /** Raw-count cap fed to x[46]/x[47]; the model log-compresses internally. */
    const val COUNT_CAP = 100

    /** TopCandidateType enum codes from the source model. Only 13 is verified (suppressed). */
    const val TYPE_CODE_MAX = 33
    const val TYPE_WORD = 0
    const val TYPE_SUPPRESSED = 13

    /**
     * z-scored input positions and their population means, from the model's
     * own preprocessing constants (preprocess_slices.json). Filling the mean
     * makes the in-model z-score exactly 0 — "population average" — for every
     * decoder feature Shade cannot reproduce. Positions NOT listed here pass
     * through raw (or feed flag/derived features) and stay 0.
     */
    private val Z_POSITIONS = intArrayOf(
        1, 3, 8, 9, 10, 12, 13, 15, 16, 17, 22,
        27, 28, 29, 30, 31, 32, 33, 34,
        36, 37, 38, 39, 40, 41, 42, 43, 44, 45
    )
    private val Z_MEANS = floatArrayOf(
        -11.868727684020996f,   // 1  — LM-score-like (mean −11.87, σ 8.89)
        -16.108646392822266f,   // 3  — LM-score-like (mean −16.11, σ 11.24)
        -11.860713005065918f,   // 8  — LM-score-like
        3.7086970806121826f,    // 9  — veto-guarded feature (> 20 forces out = −1)
        4.737763404846191f,     // 10 — veto-guarded feature (> 20 forces out = −1)
        0.1682620793581009f,    // 12
        0.07510673254728317f,   // 13
        0.19201767444610596f,   // 15
        0.10502961277961731f,   // 16
        0.5961264371871948f,    // 17
        0.529959499835968f,     // 22
        1.072770118713379f,     // 27
        0.04370403662323952f,   // 28
        0.005614586174488068f,  // 29
        0.24214617908000946f,   // 30
        0.031911496073007584f,  // 31
        1.3719323873519897f,    // 32
        95.1851806640625f,      // 33 — history-size stat (raw scale, mean 95)
        134.48724365234375f,    // 34 — history-size stat (raw scale, mean 134)
        0.10597605258226395f,   // 36
        0.15462397038936615f,   // 37
        2.081317663192749f,     // 38
        0.7217158675193787f,    // 39
        0.0f,                   // 40
        0.0007014714647084475f, // 41
        0.0002284557995153591f, // 42
        0.011135885491967201f,  // 43
        0.09498659521341324f,   // 44
        0.0010307022603228688f  // 45
    )

    /**
     * @param candidate        the candidate word being scored (sequence A)
     * @param typedWord        what the user actually typed (sequence B)
     * @param typeCode         candidate type code 0..33 ([TYPE_WORD] for words)
     * @param userUnigramCount raw persistent usage count of [candidate] (x[46])
     * @param userBigramCount  raw count of (previous word → candidate) (x[47])
     */
    fun extract(
        candidate: String,
        typedWord: String = "",
        typeCode: Int = TYPE_WORD,
        userUnigramCount: Int = 0,
        userBigramCount: Int = 0
    ): FloatArray {
        val f = FloatArray(FEATURE_SIZE)

        // 1) Neutral fill: population mean at every z-scored decoder position
        //    (in-model z-score becomes 0 — statistically neutral, keeps the
        //    network in its live regime instead of the all-zero no-op zone).
        for (i in Z_POSITIONS.indices) f[Z_POSITIONS[i]] = Z_MEANS[i]

        // 2) User-history counts, raw (the model applies log(v+1) itself).
        f[46] = userUnigramCount.coerceIn(0, COUNT_CAP).toFloat()
        f[47] = userBigramCount.coerceIn(0, COUNT_CAP).toFloat()

        // 3) Char sequences: A = candidate, B = typed word. ASCII-only, ≤15
        //    slots, zero-padded; out-of-table codes are dropped, never wrapped.
        encodeChars(candidate, f, CHAR_A_START)
        encodeChars(typedWord, f, CHAR_B_START)

        // 4) Candidate type code (0..33).
        f[82] = typeCode.coerceIn(0, TYPE_CODE_MAX).toFloat()
        return f
    }

    private fun encodeChars(text: String, f: FloatArray, start: Int) {
        var slot = 0
        for (ch in text.lowercase()) {
            if (slot >= CHAR_SLOTS) break
            val code = ch.code
            if (code in 1..MAX_CHAR_CODE) {
                f[start + slot] = code.toFloat()
                slot++
            }
        }
    }
}
