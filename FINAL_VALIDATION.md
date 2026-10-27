# Final validation notes

Applied latest changes:

- emoji suggestions now appear in the emoji panel, not the word suggestion strip,
- engine interfaces added,
- emoji prediction engine added,
- candidate ranker added,
- dictionary sources consolidated under `assets/languages/<locale>/dictionary.txt`,
- starter unit tests and CI workflow added,
- previous settings/neural/motion/crash-audit changes retained.

Static validation completed:

- resource XML parses,
- missing string/drawable/style references were checked,
- key source files and tests are present,
- stale inline emoji-suggestion code was removed.

A full Gradle compile was attempted but did not complete inside the execution time limit.
Run locally:

```bash
bash gradlew test
bash gradlew assembleDebug
```

## Packaging revert note

Shade-owned custom model packaging was removed. The project is back to direct ONNX assets (`model_<lang>.ort` + `vocab_<lang>.tsv`) for simplicity.

## Voice typing integration

Added a mic toolbar action using Android speech recognition. Users must grant
`RECORD_AUDIO` permission for voice input to work.

## Slide typing integration

Added basic slide decoding using actual touch paths, nearest-key sequence,
dictionary suggestions, and spatial reranking.

## Voice/slide scrutiny fixes

- Voice permission denial now opens Shade's app settings so users can grant mic access.
- Slide input now requires meaningful movement before it consumes the key press, reducing accidental swipe triggers from tap jitter.
- Slide decoding requires a slightly longer raw key sequence.

## CI setup-android fix

Removed the `android-actions/setup-android` CI step. Newer GitHub Ubuntu runner
images no longer provide the legacy `tools` SDK package that the action tries to
install. Android SDK tooling is already present on the runner image.

Also updated workflow actions to current v5 versions to avoid Node.js 20
deprecation warnings.

## Suggestion quality pass

- Neural prefix reranking can now inject strong neural candidates instead of only
  reordering dictionary candidates.
- Neural confidence now influences blend weight instead of always applying one fixed mix.
- Exact/prefix matches receive explicit bonuses.
- Context window increased from 16 to 24 tokens where supported by the model.

## JUnit dependency fix

Added `junit:junit:4.13.2` as a unit-test dependency. The main app compiled
successfully; CI was failing only while compiling the starter unit tests.

## Phrase booster

Added a lightweight phrase-continuation layer for common conversational patterns
such as `how → are` and `are → you`, before dictionary fallback.

## SwiftKey-style context suggestion pass

- Next-word candidates now use the previous two words when available.
- Prefix suggestions can now inject context-aware continuations, e.g. after `how`,
  typing `a` can surface `are`.
- Phrase continuations now support typed-prefix filtering.

## Advanced grammar layer

Added a lightweight on-device grammar engine for common English corrections:

- contractions (`dont` → `don't`),
- capitalization of standalone `i`,
- subject-verb agreement (`i is` → `i am`, `he dont` → `he doesn't`),
- auxiliary/verb continuations after subjects,
- grammar-aware suggestion ordering.

## Event-based correction revert

Corrections now record a short-lived correction event. Pressing backspace immediately
after an autocorrect restores the user's originally typed text instead of deleting the
corrected word. The event expires quickly and is cleared when the user keeps typing.
