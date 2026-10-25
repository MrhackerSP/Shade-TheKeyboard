# Shade crash/jank audit

## Fixed

1. Neural inference is no longer executed on the main thread.
   - `predictNext()` and `rerankPrefix()` now run on `Dispatchers.Default`.
   - This reduces ANR risk and keyboard jank.

2. Neural engine reload is synchronized.
   - Prevents duplicate engine creation during startup/preference reload races.

3. Engine shutdown path is guarded.
   - `onDestroy()` closes the current predictor and clears references.

4. Missing neural assets fail soft.
   - If `model_<lang>.ort` or `vocab_<lang>.tsv` is unavailable, Shade falls back to dictionary/bigram suggestions.

5. KeyboardView edge-case hardening.
   - Avoids `first()` on an empty visible-key list.

6. ONNX edge-case hardening.
   - Detects missing model inputs/outputs instead of throwing obscure runtime errors.

## Still required in real device testing

- Cold-start test with `model_en.ort` present.
- Rapid language switching.
- Rotation while suggestions are active.
- Low-RAM device test.
- Long typing sessions to detect native memory growth.
- Android Studio StrictMode / FrameMetrics review.

## English vocabulary/model off-by-one alignment

Detected that `model_en.ort` has 20,000 output classes while the decoded English vocabulary contains 20,001 IDs including the empty placeholder ID 0. `NeuralVocabulary.topK()` now shifts model output indices by one when this exact pattern is present, so class 0 maps to vocabulary ID 1 instead of dropping the final vocabulary entry.
