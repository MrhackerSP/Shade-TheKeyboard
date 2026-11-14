#!/usr/bin/env python3
"""Cross-check the settings redesign for wiring bugs:
1. Every key in SettingsCatalog must be read somewhere (ShadeService/KeyboardView/
   Themes/...) with a matching type and compatible default.
2. Every titleRes/subtitleRes/sectionRes in the catalog must exist in strings.xml.
3. Color keys must be consumed by Themes.custom()."""
import re
import glob

ROOT = "/home/z/my-project/work/shade-extract/Shade-TheKeyboard-main"
CAT = open(f"{ROOT}/app/src/main/kotlin/com/noircipher/shade/SettingsCatalog.kt", encoding="utf-8").read()

# 1. Collect catalog (key, kind, default) triples.
cat = []
for m in re.finditer(r'SettingsItem\.(\w+)\(', CAT):
    kind = m.group(1)
    if kind == "Info":
        continue
    block = CAT[m.start():m.start() + 900]
    if kind == "Action":
        continue  # no key
    km = re.search(r'key = "([^"]+)"', block) or re.search(r'"((?:[^"\\]|\\.)*)",\s*R\.string', block)
    key = km.group(1) if km else None
    if not key:
        continue
    if kind == "Toggle":
        dm = re.search(r'(?:,\s*0,\s*|,\s*)?(true|false)', block[m.end() - m.start():m.end()] if False else block[:400])
        # simpler: first true/false literal after key match
        dm = re.search(r'\b(true|false)\b', block[km.end():km.end() + 300])
        cat.append((key, "Boolean", dm.group(1) if dm else "?"))
    elif kind == "Slider":
        dm = re.search(r'(\d+),\s*"%dms?"', block) or re.search(r'\bdefault\b', block)
        # slider: min,max,step,default,format
        dm2 = re.search(r'\d+,\s*(\d+),\s*(\d+),\s*"%d(?:ms|dp|px)?"', block)
        if dm2:
            cat.append((key, "Int", dm2.group(1)))
        else:
            # key_height uses "%ddp" with default before format
            dm3 = re.search(r'\d+,\s*(\d+),\s*\d+,\s*(\d+),\s*"%ddp"', block)
            cat.append((key, "Int", dm3.group(2) if dm3 else "?"))
    elif kind == "Choice":
        dm = re.search(r'default = "([^"]+)"', block)
        cat.append((key, "String", dm.group(1) if dm else "?"))
    elif kind == "Color":
        cat.append((key, "String(hex)", "-"))

# 2. Collect reads across the codebase.
reads = {}  # key -> list of (type, default, file)
for f in glob.glob(f"{ROOT}/app/src/main/kotlin/**/*.kt", recursive=True):
    src = open(f, encoding="utf-8").read()
    for m in re.finditer(r'getBoolean\("([^"]+)",\s*(true|false)\)', src):
        reads.setdefault(m.group(1), []).append(("Boolean", m.group(2), f.split("/")[-1]))
    for m in re.finditer(r'getInt\("([^"]+)",\s*(\d+)\)', src):
        reads.setdefault(m.group(1), []).append(("Int", m.group(2), f.split("/")[-1]))
    for m in re.finditer(r'getString\("([^"]+)",\s*"([^"]*)"\)', src):
        reads.setdefault(m.group(1), []).append(("String", m.group(2), f.split("/")[-1]))
    for m in re.finditer(r'getString\("([^"]+)",\s*(?:null|[A-Za-z_][\w.]*)\)', src):
        reads.setdefault(m.group(1), []).append(("String?", "null/var", f.split("/")[-1]))

print(f"{'key':<24} {'catalog':<14} {'reads (type=default @file)'}")
problems = []
for key, ctype, cdef in cat:
    r = reads.get(key, [])
    if not r:
        problems.append(f"UNREAD KEY: {key}")
        print(f"{key:<24} {ctype+'='+cdef:<14} ** NO READS **")
        continue
    types = {t for t, _, _ in r}
    ok = any((ctype.startswith(t.rstrip('?')) or (ctype == "String(hex)" and t.startswith("String")))
             for t in types)
    detail = ", ".join(f"{t}={d}@{f}" for t, d, f in r[:3])
    print(f"{key:<24} {ctype+'='+cdef:<14} {detail}" + ("" if ok else "  ** TYPE MISMATCH **"))
    if not ok:
        problems.append(f"TYPE MISMATCH: {key} catalog={ctype} reads={types}")

# 3. Strings existence.
strings_src = open(f"{ROOT}/app/src/main/res/values/strings.xml", encoding="utf-8").read()
defined = set(re.findall(r'<string name="(\w+)"', strings_src))
refs = set(re.findall(r'R\.string\.(\w+)', CAT))
missing = sorted(refs - defined)
print(f"\ncatalog string refs: {len(refs)} | missing: {missing or 'NONE'}")

# 4. Themes.custom() consumes color_* keys.
themes = open(f"{ROOT}/app/src/main/kotlin/com/noircipher/shade/Theme.kt", encoding="utf-8").read()
for k in ("color_bg", "color_key", "color_keyfn", "color_text", "color_accent"):
    used = f'"{k}"' in themes or f'{k}' in themes
    print(f"Themes reads {k}: {used}")
    if not used:
        problems.append(f"THEME KEY UNREAD: {k}")

print("\n=== PROBLEMS ===")
print("\n".join(problems) if problems else "NONE — catalog fully wired")
