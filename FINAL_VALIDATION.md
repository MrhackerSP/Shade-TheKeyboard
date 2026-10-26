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
