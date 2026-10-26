# Shade full patch notes

## Settings UI

- Strict pitch-black monochrome theme (`#000000` background).
- Near-black settings cards with hairline strokes.
- Uppercase section labels and subtitle-rich settings rows.
- Circular monochrome icon set and RTL-safe vector arrows.
- Ripple feedback, focusability, clickability, and larger touch targets.
- Dynamic ActionBar titles and back navigation on subscreens.
- Missing settings string resources added.
- Alert dialogs aligned to the pitch-black theme.
- Placeholder settings rows now show "Coming soon".

## Neural engine

- Added ONNX Runtime Android dependency.
- Added optional neural prediction preference.
- Added `com.noircipher.shade.ai` package:
  - `NeuralVocabulary.kt`
  - `NeuralPredictor.kt`
  - `OnnxNeuralPredictor.kt`
  - `NeuralPredictionEngine.kt`
- Integrated neural loading, lifecycle cleanup, next-word merging, and prefix reranking in `ShadeService.kt`.
- Added `app/src/main/assets/neural/README.txt` with model/vocab contract.

Optional assets:

```text
app/src/main/assets/neural/model.ort
app/src/main/assets/neural/vocab.tsv
```

Expected ONNX model contract:

```text
input:  int64[1, 16]
output: float32[1, vocab_size]
```

If model assets are absent, Shade continues using dictionary/bigram suggestions.

## Build

Run:

```bash
./gradlew assembleDebug
```

## Motion polish

- Added `ShadeMotion.kt` with public Android/Google-style motion curves and shared durations.
- Standardized key press, popup, candidate, layout, panel, and swipe-trail animations.
- Replaced bouncy `OvershootInterpolator` feedback with restrained `FastOutSlowIn`/`FastOutLinearIn`.
- Kept key feedback short: 40 ms down, 110 ms up.
- Kept candidate updates subtle: 110 ms fast-out/slow-in.
- Added consistent popup/panel timing for a smoother Gboard-like feel.

## Bundled language assets

Added language-specific neural assets:

```text
app/src/main/assets/languages/en_US/model_en.ort
app/src/main/assets/languages/en_US/vocab_en.tsv
app/src/main/assets/languages/hg_IN/vocab_hg.tsv
```

The engine now looks for language-specific files first:

```text
neural/model_<lang>.ort
neural/vocab_<lang>.tsv
```

English uses:

```text
model_en.ort
vocab_en.tsv
```

Hinglish/Hindi-English vocabulary is bundled as:

```text
vocab_hg.tsv
```

No Hinglish ONNX model is bundled yet, so Hinglish uses dictionary/bigram
fallback unless `model_hg.ort` is added later.

## Hindi vocabulary asset

Added:

```text
app/src/main/assets/languages/hi_IN/vocab_hi.tsv
```

The language router now maps Hindi locales/tags to `hi` and Hinglish tags to `hg`.
Add `model_hi.ort` later to enable Hindi neural predictions.

## Crash/jank audit

- Moved neural inference off the main thread.
- Synchronized neural engine reloads.
- Added `CRASH_AUDIT.md` with fixed issues and device-test checklist.

## Bundled language packs inside app assets

Organized language packs are now included under:

```text
app/src/main/assets/languages/
```

so they live inside the app folder structure instead of as a separate ZIP section.

## Hindi/Hinglish baseline ONNX models

Generated valid ONNX Runtime models:

```text
app/src/main/assets/languages/hi_IN/model_hi.ort
app/src/main/assets/languages/hg_IN/model_hg.ort
```

Purpose:

- verify Hindi/Hinglish language routing,
- verify vocabulary/model alignment,
- enable end-to-end neural pipeline testing.

These are unigram-prior baseline models generated from vocabulary order, not trained
production-quality language models.

## Runtime language assets moved into per-language folders

Raw `.lm/.lm1/.lm2` source containers are no longer bundled as Shade runtime assets.
Shade now loads decoded vocabularies and ONNX models from:

```text
app/src/main/assets/languages/<lang>/
```

This avoids shipping proprietary source containers inside the app runtime bundle.

## English vocabulary/model off-by-one alignment

Detected that `model_en.ort` has 20,000 output classes while the decoded English vocabulary contains 20,001 IDs including the empty placeholder ID 0. `NeuralVocabulary.topK()` now shifts model output indices by one when this exact pattern is present, so class 0 maps to vocabulary ID 1 instead of dropping the final vocabulary entry.

## Emoji panel suggestions

Emoji predictions are no longer inserted into the normal word suggestion strip.
When the user opens the emoji panel, Shade shows emoji suggestions in a top row
based on the current typed word / previous word.

## Engine contracts and candidate helpers

Added engine interfaces and helper classes under:

```text
app/src/main/kotlin/com/noircipher/shade/engine/
```

## Dictionary consolidation

Canonical dictionaries are now loaded from:

```text
app/src/main/assets/languages/<locale>/dictionary.txt
```

Legacy `wordlists/<locale>.txt` paths remain as fallback only.

## Tests and CI

Added starter unit tests and a GitHub Actions workflow.

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
