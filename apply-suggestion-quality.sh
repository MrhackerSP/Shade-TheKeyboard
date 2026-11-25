#!/usr/bin/env bash
# apply-suggestion-quality.sh — robust installer for shade-suggestion-quality.patch
# (generated against 52bc2e2).
#
# Why: plain `git apply` is all-or-nothing and matches hunks byte-exactly.
# If ANY touched file has a local uncommitted edit (e.g. a hand-tweak around
# SuggestionPipeline.kt:131), or your checkout uses CRLF line endings, plain
# apply refuses and NOTHING is applied. This installer tries progressively
# more forgiving strategies and tells you exactly what happened.
#
# Usage (from the root of your Shade git repo):
#   bash apply-suggestion-quality.sh [path/to/shade-suggestion-quality.patch]
set -u

PATCH=${1:-shade-suggestion-quality.patch}
[ -f "$PATCH" ] || { echo "ERROR: patch file not found: $PATCH"; exit 2; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
  echo "ERROR: run this from inside your Shade git repo"; exit 2; }

BASE=52bc2e2
HEAD=$(git rev-parse --short HEAD)
if [ "$HEAD" != "$BASE" ]; then
  echo "NOTE: patch was generated against $BASE; your HEAD is $HEAD."
  echo "      That is fine — the 3-way fallback below merges around drift."
fi

# Diagnostic: which patch files are locally modified right now?
TOUCHED=$(grep '^diff --git' "$PATCH" | awk '{print $3}' | sed 's|^a/||')
LOCAL_EDITS=""
for f in $TOUCHED; do
  git diff --name-only HEAD -- "$f" | grep -q . && LOCAL_EDITS="$LOCAL_EDITS $f"
done
if [ -n "$LOCAL_EDITS" ]; then
  echo "WARNING: these patch targets have local uncommitted edits:"
  for f in $LOCAL_EDITS; do echo "  - $f"; done
  echo "  (This is the usual cause of 'patch does not apply'. If you do not"
  echo "   need those edits: git checkout -- <file>, then re-run.)"
  echo ""
fi

# Strategy 1: clean apply.
if git apply --check "$PATCH" 2>/dev/null; then
  git apply "$PATCH"
  echo "OK: patch applied cleanly ($(git status --porcelain | wc -l | tr -d ' ') files touched)."
  exit 0
fi

# Strategy 2: whitespace/CRLF drift.
if git apply --check --ignore-whitespace "$PATCH" 2>/dev/null; then
  git apply --ignore-whitespace "$PATCH"
  echo "OK: applied with --ignore-whitespace (CRLF or whitespace drift)."
  exit 0
fi

# Strategy 3: 3-way merge using the blob ids embedded in the patch.
# Merges AROUND local edits; real conflicts are left as <<<<<<< markers.
if OUT=$(git apply --3way "$PATCH" 2>&1); then
  echo "OK: applied via 3-way merge."
  exit 0
fi
CONFLICTS=$(grep -rls '^<<<<<<< ' --include='*.kt' --include='*.xml' --include='*.kts' app 2>/dev/null || true)
if [ -n "$CONFLICTS" ]; then
  echo "3-way merge left conflict markers in:"
  for f in $CONFLICTS; do echo "  - $f"; done
  echo "Resolve them, then: git add <files> && git commit."
  exit 1
fi

# Strategy 4: per-hunk reject + already-applied detection.
echo ""
echo "Falling back to per-hunk application (--reject)..."
git apply --reject "$PATCH" 2>&1 | sed 's/^/  /' || true

LEFT=0
for rej in $(find . -name '*.rej' -not -path './.git/*'); do
  target=${rej%.rej}
  ok=1
  while IFS= read -r added; do
    added=${added#+}
    [ -n "$added" ] || continue
    grep -qF -- "$added" "$target" 2>/dev/null || { ok=0; break; }
  done < <(grep '^+' "$rej" | grep -v '^+++')
  if [ "$ok" -eq 1 ]; then
    rm -f "$rej"
    echo "  ALREADY APPLIED (skipped safely): $target"
  else
    LEFT=$((LEFT+1))
    echo "  NEEDS MANUAL ATTENTION: $rej (target: $target)"
  fi
done

echo ""
if [ "$LEFT" -eq 0 ]; then
  echo "DONE: the full patch is in place. No manual work needed."
else
  echo "DONE with $LEFT conflicted file(s). Open the .rej file(s) above and merge by hand,"
  echo "or run: git checkout -- <file> && re-run this script."
fi
