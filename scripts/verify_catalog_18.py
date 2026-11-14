#!/usr/bin/env python3
"""Verify the regenerated EmojiCatalog.kt covers the official Emoji 15.1 base set
(superset check) plus all Emoji 16.0/17.0/18.0 additions. Extracts Category(...)
blocks directly, no unicode_escape mangling."""
import re

ROOT = "/home/z/my-project/work/shade-extract/Shade-TheKeyboard-main"
NEW = open(f"{ROOT}/app/src/main/kotlin/com/noircipher/shade/EmojiCatalog.kt", encoding="utf-8").read()

strip = lambda s: s.replace("\uFE0F", "")

new_all = []
for m in re.finditer(r'Category\("([^"]*)", "([^"]*)", listOf\(([^)]*)\)\)', NEW):
    body = m.group(3)
    emojis = re.findall(r'"([^"]*)"', body)
    new_all.extend(emojis)
print(f"new catalog emojis: {len(new_all)} (dedup {len(set(new_all))})")
dupes = [e for e in set(new_all) if new_all.count(e) > 1]
print("duplicates:", dupes or "none")
ascii_leak = [e for e in set(new_all) if any(c.isalpha() and c.isascii() for c in e)]
print("ascii-letter leaks:", ascii_leak or "none")
blank = [e for e in set(new_all) if not e.strip()]
print("blank entries:", blank or "none")

# Official 15.1 base set (fully-qualified, non-skin-tone, non-component).
old_base = set()
group = None
for line in open("/tmp/emoji-test-15.1.txt", encoding="utf-8"):
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
    status, meta = rest.split("#", 1)
    if "fully-qualified" not in status:
        continue
    mm = re.split(r"\s+E\d+\.\d+\s+", meta.strip(), maxsplit=1)
    if len(mm) != 2 or "skin tone" in mm[1].lower():
        continue
    seq = "".join(chr(int(h, 16)) for h in left.split())
    old_base.add(strip(seq))

new_set = {strip(e) for e in new_all}
missing = sorted(old_base - new_set)
print(f"official 15.1 base: {len(old_base)}")
print("15.1 emojis missing from new catalog:", missing[:20] or "none")

groups = {
    "16.0": ["🫩", "🫆", "🪾", "🫜", "🪉", "🪏", "🫟"],
    "17.0": ["🫪", "🫇", "🫈"],
    "18.0": ["🫫", "🫹", "🫺", "🫌", "🫝", "🛙", "🪋", "🪌", "🪍"],
}
for ver, emojis in groups.items():
    absent = [e for e in emojis if e not in new_all]
    print(f"{ver}: {'ALL PRESENT' if not absent else 'MISSING ' + str(absent)}")

m = re.search(r"TOTAL_EMOJIS: Int = (\d+)", NEW)
print("TOTAL_EMOJIS const:", m.group(1), "| flat count:", len(new_all),
      "|", "MATCH" if int(m.group(1)) == len(new_all) else "MISMATCH")
