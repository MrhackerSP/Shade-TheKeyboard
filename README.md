# Shade

An Android keyboard (input method) written from scratch in Kotlin. Everything that can
run on the device runs on the device: typing, correction, next-word prediction, glide
decoding, emoji suggestion and learned personalisation. The only network features are
separate, explicit tools (GIF search, translation) and the update check.

MIT licensed.

---

## Features

### Typing & layout
- Custom-drawn key view (no XML key grids): letter / symbol / symbol-2 / number-pad
  modes, QWERTY-family layouts plus a user-defined custom layout string.
- Shift, caps-lock, auto-capitalisation honouring the field's caps mode, and
  double-space → period.
- Long-press accent panels: a grid sized to the accent count, drawn in a window-level
  overlay above every row, with a scrim while open; releasing on the key commits its
  number/default accent.
- Key preview bubbles, number hints, key borders, key height, keyboard opacity and
  see-through background controls.
- Split keyboard, one-handed mode (left/right), and a resize/float mode with drag
  handles (vertical resize, side narrowing, long-press-drag to float).
- Space-bar swipe moves the cursor; long-press space opens a trackpad cursor mode (or
  the system input-method picker when the trackpad is off); long-press Enter opens the
  one-handed/position menu.
- Delete key: hold to repeat, slide left to select characters or words (finger speed
  picks the unit) with a live selection count badge.
- Background image behind the keys with fit/zoom/pan/size, dim and blur controls.

### Themes
- System-driven theme plus fixed light/dark themes, and per-element custom colours
  (background, keys, function keys, text, accent) edited with a built-in HSV colour
  picker with presets and a hex field.
- Optional Material You palette derived from the current wallpaper, re-derived live
  when the wallpaper changes.

### Suggestions, correction & learning
- Multilingual dictionaries (English, Hindi and Hinglish word lists ship in assets);
  several can be enabled at once and user word lists can be imported.
- Candidate sources merged per keystroke: neural prefix prediction (ONNX language
  model), dictionary continuation, bigram/trigram context rerank, grammar and
  heuristic engines, phrase completions, sentence starters, time-aware words, smart
  numbers, per-app vocabulary and confusable-pair fixes.
- Autocorrect gated by a confidence score (edit cost, rank margin, touch geometry)
  with a typing-speed-adjusted threshold; backspace reverts the last correction
  wholesale; URL/email/password fields suppress composing and correction.
- A trained TFLite candidate ranker plus an on-device online logistic trainer
  personalise ordering from what you accept and reject; a calibrated acceptance-rate
  adapter scales the learning rate.
- Learned words, n-grams, touch bias and ranker weights never leave the device; the
  learned-word map is stored in a Keystore-encrypted file written atomically
  (fsync + rename, fail-closed if encryption or decryption is unavailable).
- Privacy gates: incognito mode, `IME_FLAG_NO_PERSONALIZED_LEARNING` fields and
  password fields disable every personalisation path; sensitive clipboard clips are
  never captured.
- Offensive-word filter for suggestions and autocorrect (your own typed text is never
  rewritten by it).
- Suggestion bar casing follows shift state and what you typed; long-press a
  suggestion to remove it from the bar and from learned memory.

### Glide (swipe) typing
- Every dictionary word is a candidate: each word is drawn as the polyline through its
  letters and matched against the finger path (start/end anchoring, DP letter
  alignment, uniform-resample shape comparison, point-to-polyline adherence, corner
  placement, loop detection for doubled letters, typed-error decomposition).
- A trained reranker (`swipe_model.json`, fitted by `scripts/train_swipe_model.py` on
  simulated swipes) corrects the hand-tuned score; its influence is clamped so it can
  refine close calls but never overrule a confident geometric match.
- Neural language-model context (previous words) participates in stage-1 scoring to
  resolve lookalike paths.
- Live composing preview while the finger moves; a glow ribbon is the only visual
  feedback during a glide — keys do not highlight or pop.

### Voice typing
- Voice input with a live partial-transcript chip over the keyboard; the engine policy
  prefers an on-device recognizer when the system exposes one and falls back to the
  platform speech service otherwise.

### Emoji
- Emoji panel with integrated search over curated keyword catalogs (CLDR names,
  shortcut words, concept keywords; Hindi included), generated from the official
  Unicode charts by `scripts/gen_emoji_catalog.py` and checked by
  `scripts/verify_catalog_18.py`.
- Context-aware emoji suggestions while typing, scored by on-device concept and
  emotion models (`emoji_expression/*.tflite`); recently used emoji surface first.

### Clipboard & toolbar
- Clipboard history with pinning and image clips, stored in the app's private
  storage; a copied snippet appears as a suggestion-bar chip when the bar is empty.
- Configurable toolbar: clipboard, emoji, GIF, translate, numpad, cursor left/right,
  select all, copy, cut, paste, undo, proofread, incognito, one-handed, resize,
  voice, settings.

### Tools
- **Proofread**: conservative batch correction of a selection (or the text around the
  cursor) using the same correction machinery as typing; one batch edit, so the app's
  undo reverses it wholesale.
- **Translate**: a panel with its own input field and a cycling target-language chip
  (11 languages); text is chunked at sentence boundaries and translated through
  public key-less backends tried in order. *Network feature.*
- **GIF search**: online GIF picker; opens into a setup card until an API key is
  provided, sends as inline content where the app accepts it and as a link otherwise;
  blocked in secure fields. *Network feature.*
- **Typing insights** (opt-in): local-only statistics, key heat-map and per-key touch
  bias that personalises spatial correction.
- **Update check**: downloads a release APK over HTTPS and installs via the system
  package installer; auto-check is off by default.

### Backup & portability
- JSON backup/restore of settings and learned words.
- Gboard-style settings: a home of grouped category cards (Languages, Preferences, Theme / Corrections and suggestions, Glide typing, Voice typing, Emojis and GIFs / Clipboard, Dictionary / Privacy, About, Advanced settings, Shade updates), each opening its own page, with Gboard's check / X switch. Pages and rows live in `SettingsCatalog`; rendering is in `SettingsAdapter`.
- Import of words exported from another keyboard (backup archive, dictionary JSON or
  plain word list) into the on-device learned memory.
- Optional training-data export for your own experiments.

---

## Architecture

| Package | Responsibility |
|---|---|
| `com.shade.keyboard` | IME service, key view, popup/accent overlay, suggestion pipeline, clipboard, settings, backup, theming, proofreader, updater |
| `.slide` | Glide decoding: lexicon coding, geometry, two-stage decoder, trained reranker model |
| `.engine` | Correction & ranking engines: neural spell checker, confusables, phonetics, phrases, online ranker, calibration, suppression, TFLite scorers |
| `.ai` | ONNX next-word language model and vocabulary |
| `.emoji` | Emoji prediction engines |
| `.gif`, `.translate`, `.voice` | The three I/O peripherals (network GIF, network translation, speech) |
| `.analytics` | Opt-in typing statistics, heat-map view, touch-bias model |
| `.keypresseffect` | Haptics/sound engine (mode resolution, pref gates, rate limiting) |
| `.migration` | Foreign dictionary/backup import |
| `.motion` | Shared animation constants |

Concurrency model: keystroke work runs on the main thread only for editor calls;
suggestion and decode pipelines run on background dispatchers behind a debounced,
cancellable job. Shared ranking state is published by volatile reference swap (never
mutated in place). Neural language-model engines are parked per locale (current +
previous) instead of being reloaded on every script flip, and dropped under memory
pressure.

---

## Building

Requirements: JDK 17, Android SDK platform 37 with build-tools 37.0.0, and Gradle via
the wrapper (9.8). The root build script declares only the Android Gradle plugin
(9.4) — Kotlin support ships built into AGP 9.x, so no separate Kotlin plugin is
applied.

```bash
./gradlew assembleDebug          # debug APK
./gradlew test                   # unit tests (460+, incl. Robolectric smoke tests)
./gradlew lintDebug              # lint gate
```

Release builds are signed with a keystore you provide:

```bash
cp keystore.properties.example keystore.properties   # then fill in paths/passwords
./gradlew assembleRelease        # APK
./gradlew bundleRelease          # AAB for Play
```

Without `keystore.properties` the release variant falls back to the debug keystore so
CI artifacts stay installable — never distribute those.

`minSdk` 26, `targetSdk`/`compileSdk` 37. APK signing uses v2 only (minSdk 26 does not
need v1 jar signing).

### CI

GitHub Actions (`.github/workflows/ci.yml`) runs on every push and PR: unit tests →
`lintDebug` → debug APK → minified, resource-shrunk release APK (R8 breakage is a
blocking failure), uploading both APKs as artifacts and the HTML/XML test reports
whenever the test step fails.

---

## Reproducible assets

| Script | Purpose |
|---|---|
| `scripts/train_swipe_model.py` | Simulates swipes over the dictionary and fits the glide reranker (deterministic; emits `swipe_model.json`) |
| `scripts/gen_emoji_catalog.py` | Regenerates the emoji keyword catalogs from Unicode/CLDR data |
| `scripts/add_emoji18_keywords.py` | Keyword coverage for the newest emoji batch |
| `scripts/verify_catalog_18.py` | Asserts catalog completeness against the Unicode chart |
| `scripts/verify_settings_wiring.py` | Checks every settings catalog row is wired to a real preference read |

The bundled models (ONNX language model, TFLite ranker / next-word / spell / concept /
emotion encoders) are loaded read-only from assets; every loader degrades gracefully
to the unaided path when a model is missing or rejects its payload.

---

## Provenance

The glide decoder is an original implementation. Its design was shaped by the
published SHARK2 ideal-word-path algorithm (Kristensson & Zhai, 2004) and by the
public behaviour of FUTO Keyboard's on-device glide typing; no source code or model
data from either is used. Reranker weights are produced by Shade's own simulator and
can be retrained from source.

## License

MIT — see `LICENSE`.
