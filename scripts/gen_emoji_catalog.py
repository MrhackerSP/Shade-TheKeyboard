#!/usr/bin/env python3
"""
Generate the Shade emoji panel catalog from the official Unicode CLDR emoji list.

Source (Emoji 18.0, Unicode 18.0, released September 2026):
  primary: https://www.unicode.org/Public/emoji/18.0/emoji-test.txt
  fallback: https://www.unicode.org/emoji/charts-18.0/full-emoji-list.html
  (unicode.org had not published the Public/emoji/18.0 data file yet, so the
  script falls back to the official full-emoji-list chart, which carries the
  same groups and CLDR names but no skin-tone rows and no components.)

Outputs:
  1. app/src/main/kotlin/com/noircipher/shade/EmojiCatalog.kt
     - one category per emoji-test group (Components skipped), base variants only
     - skin-tone variants excluded (the panel exposes them via long-press popup)
  2. app/src/main/assets/emoji/emoji_keywords_catalog.tsv
     - "CLDR name -> emoji" search keywords for every catalog emoji that the
       existing keyword tables (en/hi/concepts/shortcuts) do not already cover.
       Loaded ONLY by EmojiPanel's search, never by the typing-suggestion engine,
       so typing suggestions keep their existing behavior.

Run:  python3 scripts/gen_emoji_catalog.py
"""

import html
import os
import re
import urllib.request

ROOT = "/home/z/my-project/work/shade-extract/Shade-TheKeyboard-main"
ASSET_DIR = os.path.join(ROOT, "app/src/main/assets/emoji")
KT_OUT = os.path.join(ROOT, "app/src/main/kotlin/com/noircipher/shade/EmojiCatalog.kt")
TSV_OUT = os.path.join(ASSET_DIR, "emoji_keywords_catalog.tsv")

VER = "18.0"
TEST_URL = f"https://www.unicode.org/Public/emoji/{VER}/emoji-test.txt"
CHART_URL = f"https://www.unicode.org/emoji/charts-{VER}/full-emoji-list.html"
CACHE_TEST = f"/tmp/emoji-test-{VER}.txt"
CACHE_CHART = f"/tmp/full-emoji-list-{VER}.html"

# The full-emoji-list chart lists text-default emojis WITHOUT the FE0F variation
# selector (e.g. U+2764 "❤" instead of the fully-qualified U+2764 U+FE0F "❤️").
# Committing those bare codepoints would render monochrome text glyphs on
# Android. Reconcile against the fully-qualified forms of the last release that
# shipped them (Emoji 15.1 — none of the Emoji 16.0/17.0/18.0 additions are
# text-default), keyed FE0F-insensitively.
LAST_QUALIFIED_URL = "https://www.unicode.org/Public/emoji/15.1/emoji-test.txt"
LAST_QUALIFIED_CACHE = "/tmp/emoji-test-15.1.txt"

# emoji-test group name -> (tab icon, panel category name)
GROUP_MAP = {
    "Smileys & Emotion": ("\U0001F600", "Smileys & Emotion"),
    "People & Body": ("\U0001F44B", "People & Body"),
    "Animals & Nature": ("\U0001F43B", "Animals & Nature"),
    "Food & Drink": ("\U0001F34E", "Food & Drink"),
    "Activities": ("\u26BD", "Activities"),
    "Travel & Places": ("\U0001F697", "Travel & Places"),
    "Objects": ("\U0001F4A1", "Objects"),
    "Symbols": ("\u2705", "Symbols"),
    "Flags": ("\U0001F3C1", "Flags"),
}


def _fetch(url, cache):
    if os.path.exists(cache) and os.path.getsize(cache) > 100000:
        with open(cache, encoding="utf-8") as f:
            return f.read()
    print("Downloading", url)
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read().decode("utf-8")
    with open(cache, "w", encoding="utf-8") as f:
        f.write(data)
    return data


def fetch():
    """Return the raw Emoji 18.0 data: emoji-test.txt if published, else the chart."""
    try:
        return _fetch(TEST_URL, CACHE_TEST), "test"
    except Exception as e:
        print(f"emoji-test.txt unavailable ({e}); using full-emoji-list chart")
        return _fetch(CHART_URL, CACHE_CHART), "chart"


def parse_test(text):
    """emoji-test.txt -> {group: [(sequence, name), ...]} fully-qualified base emojis."""
    groups = {}
    group = None
    for line in text.splitlines():
        line = line.rstrip("\n")
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            groups.setdefault(group, [])
            continue
        if group is None or line.startswith("#") or not line.strip():
            continue
        parts = line.split(";")
        if len(parts) < 2:
            continue
        left = parts[0]
        rest = ";".join(parts[1:])
        if "#" not in rest:
            continue
        status, meta = rest.split("#", 1)
        if "fully-qualified" not in status:
            continue
        if group == "Components":
            continue
        seq = "".join(chr(int(h, 16)) for h in left.split())
        meta = meta.strip()
        m = re.split(r"\s+E\d+\.\d+\s+", meta, maxsplit=1)
        if len(m) != 2 or not m[1].strip():
            continue
        name = m[1].strip()
        if "skin tone" in name.lower():
            continue
        groups[group].append((seq, name.strip()))
    return groups


def parse_chart(text):
    """full-emoji-list.html -> same shape as parse_test()."""
    groups = {}
    # Split into group chunks at bighead cells; strip vendor image cells first so
    # the 8.5 MB of embedded base64 PNGs never enter the row parser.
    text = re.sub(r"<td class='(?:andr|apple|google|twitter|facebook|messenger|"
                  r"samsung|windows|openmoji|emojipedia|dcm|joy|fxemoji|em|en|nu|"
                  r"lg|sbg|cchars|imgs|imga|imgf|icon)[^']*'>.*?</td>", "", text,
                  flags=re.S)
    chunks = re.split(r"class='bighead'>", text)[1:]
    for chunk in chunks:
        head = re.search(r"<a[^>]*>([^<]+)</a>", chunk)
        if not head:
            continue
        group = html.unescape(head.group(1)).strip()
        if group.lower().startswith("component"):
            continue
        groups.setdefault(group, [])
        for row in chunk.split("<tr>"):
            code = re.search(
                r"class='code'[^>]*>(?:<a[^>]*>)?U\+([0-9A-Fa-f]{4,6}(?:\s*U\+[0-9A-Fa-f]{4,6})*)",
                row)
            name = re.search(r"class='name'[^>]*>([^<]+)<", row)
            if not code or not name:
                continue
            # Strip presentation markers ("⊛ ", "⊜ ") and unescape entities.
            clean = html.unescape(name.group(1))
            clean = re.sub(r"^[⊛⊜⚡⚠]\s*", "", clean).strip()
            if not clean or "skin tone" in clean.lower():
                continue
            seq = "".join(chr(int(h, 16)) for h in re.split(r"\s*U\+\s*", code.group(1)) if h)
            if seq:
                groups[group].append((seq, clean))
    return groups


def last_qualified_map():
    """{stripped_fully_qualified_seq: seq_with_FE0F} from the Emoji 15.1 data file."""
    raw = _fetch(LAST_QUALIFIED_URL, LAST_QUALIFIED_CACHE)
    out = {}
    group = None
    for line in raw.splitlines():
        line = line.rstrip("\n")
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        if group is None or group == "Components" or line.startswith("#") or not line.strip():
            continue
        parts = line.split(";")
        if len(parts) < 2 or "#" not in ";".join(parts[1:]):
            continue
        left, rest = parts[0], ";".join(parts[1:])
        status = rest.split("#", 1)[0]
        if "fully-qualified" not in status:
            continue
        seq = "".join(chr(int(h, 16)) for h in left.split())
        out.setdefault(seq.replace("\uFE0F", ""), seq)
    return out


def existing_covered():
    """Emojis already searchable through the current keyword TSVs (FE0F-insensitive)."""
    covered = set()
    for fn in ("emoji_keywords_en.tsv", "emoji_keywords_hi.tsv",
               "emoji_keywords_concepts.tsv", "emoji_keywords_shortcuts.tsv"):
        path = os.path.join(ASSET_DIR, fn)
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 2 and parts[1].strip():
                    covered.add(parts[1].strip().replace("\uFE0F", ""))
    return covered


def esc(e):
    return '"' + e.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def build_keywords(groups):
    covered = existing_covered()
    lines = [
        f"# Generated by scripts/gen_emoji_catalog.py from Unicode CLDR emoji names (Emoji {VER}).",
        "# Search-only keywords for emojis not already covered by en/hi/concepts/shortcuts tables.",
    ]
    missing = []
    for group in GROUP_MAP:
        for seq, name in groups.get(group, []):
            if seq.replace("\uFE0F", "") in covered:
                continue
            missing.append((seq, name))
            base = name.lower()
            lines.append(f"{base}\t{seq}")
            if ":" in base:
                suffix = base.split(":", 1)[1].strip()
                if suffix and suffix != base:
                    lines.append(f"{suffix}\t{seq}")
    return lines, missing


def write_kotlin(groups):
    parts = []
    parts.append("package com.noircipher.shade")
    parts.append("")
    parts.append("/**")
    parts.append(" * Emoji panel catalog, generated from the Unicode CLDR fully-qualified emoji list")
    parts.append(f" * (Emoji {VER}, unicode.org) so the panel covers every standard emoji up to")
    parts.append(" * Unicode 18.0, the September 2026 release.")
    parts.append(" *")
    parts.append(" * Skin-tone variants are intentionally excluded — [EmojiPanel] surfaces them")
    parts.append(" * through the long-press popup on the base emoji. Regenerate with")
    parts.append(" * scripts/gen_emoji_catalog.py after a Unicode release; do not hand-edit the lists.")
    parts.append(" */")
    parts.append("object EmojiCatalog {")
    parts.append("")
    parts.append("    class Category(val icon: String, val name: String, val emojis: List<String>)")
    parts.append("")
    parts.append("    val categories: List<Category> = listOf(")

    seen = set()
    total = 0
    for group, (icon, name) in GROUP_MAP.items():
        emoji_list = []
        for seq, _ in groups.get(group, []):
            if seq in seen:
                continue
            seen.add(seq)
            emoji_list.append(seq)
        total += len(emoji_list)
        parts.append(f'        Category("{icon}", "{name}", listOf(')
        for i in range(0, len(emoji_list), 12):
            # trailing comma on every chunk line: Kotlin 1.4+ accepts a trailing
            # comma before the closing paren, so no special-casing for the last line
            parts.append("            " + ", ".join(esc(e) for e in emoji_list[i : i + 12]) + ",")
        parts.append("        )),")

    parts[-1] = parts[-1].rstrip(",")  # last entry: drop the trailing comma
    parts.append("    )")
    parts.append("")
    parts.append("    /** Total number of emojis in the catalog. */")
    parts.append(f"    const val TOTAL_EMOJIS: Int = {total}")
    parts.append("}")
    return "\n".join(parts) + "\n", total


def main():
    raw, kind = fetch()
    groups = parse_test(raw) if kind == "test" else parse_chart(raw)
    if kind == "chart":
        qualified = last_qualified_map()
        for g, entries in groups.items():
            entries[:] = [
                (qualified.get(seq.replace("\uFE0F", ""), seq), name)
                for seq, name in entries
            ]
        fixed = sum(1 for g in groups.values() for seq, _ in g if seq != seq.replace("\uFE0F", ""))
        print(f"restored FE0F presentation on {fixed} text-default emojis")
    for g in GROUP_MAP:
        print(f"  group {g!r}: {len(groups.get(g, []))} emojis")
    kt, total = write_kotlin(groups)
    os.makedirs(os.path.dirname(KT_OUT), exist_ok=True)
    with open(KT_OUT, "w", encoding="utf-8") as f:
        f.write(kt)
    print(f"Wrote {KT_OUT} ({total} emojis)")

    tsv_lines, missing = build_keywords(groups)
    with open(TSV_OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(tsv_lines) + "\n")
    print(f"Wrote {TSV_OUT} ({len(missing)} uncovered emojis -> {len(tsv_lines) - 2} keyword lines)")


if __name__ == "__main__":
    main()
