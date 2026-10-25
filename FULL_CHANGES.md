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
app/src/main/assets/neural/model_en.ort
app/src/main/assets/neural/vocab_en.tsv
app/src/main/assets/neural/vocab_hg.tsv
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
app/src/main/assets/neural/vocab_hi.tsv
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
app/src/main/assets/neural/model_hi.ort
app/src/main/assets/neural/model_hg.ort
```

Purpose:

- verify Hindi/Hinglish language routing,
- verify vocabulary/model alignment,
- enable end-to-end neural pipeline testing.

These are unigram-prior baseline models generated from vocabulary order, not trained
SwiftKey-quality language models.
