# Shade Keyboard — Complete Fix History (P0–P5 + Tier S + Privacy + Features)

This document lists every fix applied to this build. 50+ fixes across 6 priority tiers.

## P0 — Compile errors + data loss (7 fixes)

1. **`Clipboard.kt` duplicate `val` declaration** — removed the second `val value = image ?: return null` line that was a botched paste artifact (compile error)
2. **`onDestroy` wiped all learned state** — replaced `clear()` calls on `adaptiveWordMemory`/`appContextModel`/`autocorrectSuppressor`/`confidenceCalibrator`/`onlineRankerTrainer` with `shutdown()` (for the 2 with debounce coroutines) or no-op (the other 3 persist synchronously). `clear()` is now exclusively user-initiated.
3. **Missing `clear()` methods on `AutocorrectSuppressor` and `ConfidenceCalibrator`** — added `fun clear()` to both classes (was a compile error)
4. **`failedNeuralLocales` TTL cache cleared in `onDestroy`** — restored `synchronized(neuralLock) { failedNeuralLocales.clear() }`
5. **Privacy gating on all 5 learning components** — gated `appContextModel.setCurrentPackage`, `autocorrectSuppressor.recordRejection`, `confidenceCalibrator.recordShown`, `onlineRankerTrainer.adapt`, `confidenceCalibrator.recordAcceptance` on `personalLearningAllowed()`
6. **`lastRankedFeatures`/`lastRawRankerScores`/`lastShownCandidates` thread-safe** — switched to `ConcurrentHashMap` + `@Volatile`
7. **`confidenceCalibrator.recordPrediction(raw, false)` over-counting 8×** — removed from per-keystroke loop; moved to `setCandidates` for top-3 displayed candidates only

## P1 — Per-component privacy gating (5 helpers)

8. Added 5 per-component toggle fields (`learningAdaptiveOn`, `learningAppCtxOn`, etc.) read from prefs
9. Added 5 helper methods (`adaptiveLearningAllowed()`, `appContextLearningAllowed()`, etc.) combining global gate with per-component toggle
10. Rewired all call sites to use per-component helpers — user can disable just per-app context tracking, or just online ranker training, without disabling everything

## P2 — Algorithmic correctness (7 fixes)

11. **`AdaptiveWordMemory` double-decay** — boost is now ALWAYS ≥ 1 (never decreases score on use); decay applied only at read time in `score()`
12. **`AutocorrectSuppressor` key collision** — added NUL separator (`"\u0000"`) so `("a","bc","d")` and `("ab","c","d")` don't collide
13. **`AutocorrectSuppressor` LRU eviction** — switched from FIFO to access-ordered `LinkedHashMap(16, 0.75f, true)`
14. **`ConfidenceCalibrator.calibrated()` was dead code** — now wired into `learnedRankCandidates` to compute `modelScore` blend
15. **`learnedBoost` magnitude dominated ranker** — both `adaptiveWordMemory.score()` and `appContextModel.score()` normalized to [0,1]; boost bounded at ~0.18
16. **`AppContextModel.currentHash` race** — made `@Volatile`; capped `apps.size` at 50
17. **`OnlineRankerTrainer` no L2 reg / no schema version** — added L2 regularization (λ=1e-4) + schema version tag

## P3 — Regressions of previously-shipped fixes (5 fixes)

18. **`i → I` autocorrect English-only gate** restored (`isEnglishLocale()`)
19. **`failedNeuralLocales` monotonic clock** — `System.currentTimeMillis()` → `SystemClock.elapsedRealtime()`; added `NEURAL_FAILURE_TTL_MS` constant
20. **`withContext(Dispatchers.Main.immediate)`** restored in 2 suggestion pipelines
21. **`TfliteCandidateRankingScorer` buffer reuse + `maxBatchSize` cache** restored
22. **Debounce** restored — `suggestDebounceJob` with 32 ms delay before launching pipeline

## P4 — UI controls, backup, subtypes, settings wiring (6 fixes)

23. **Hindi + Hinglish IME subtypes** in `method.xml` — system keyboard switcher now shows 3 subtypes
24. **New `screen_learning`** in `preferences.xml` with 5 toggles + 6 clear buttons
25. **Fixed `row_preferences` mapping** — was opening `screen_toolbar`, now opens `screen_feedback`; added `row_colors` and `row_learning`
26. **`LearningDataCleaner` helper** — centralized clear-all / clear-one for 5 adaptive stores
27. **`Backup.kt` schema v2** — includes 5 adaptive stores; fixes Float/Int silent-drop bug on `user_words`
28. **`clear_learned` button** now clears `user_words` + all 5 adaptive stores

## P5 — Neural-engine perf (5 fixes, no model retrain)

29. **`aaptOptions { noCompress("ort", "tflite", "onnx") }`** in `build.gradle.kts`
30. **`AssetMmap` helper** — memory-mapped model loading via `MappedByteBuffer`; eliminates heap-doubling `readBytes()`. Used by all 4 TFLite loaders + both ONNX loaders
31. **ONNX Runtime `ORT_ENABLE_ALL` + 2 intra-op threads** in `OnnxNeuralPredictor` and `OnnxNwpPredictor`
32. **`NeuralVocabulary.topK` bounded max-heap** — O(V log limit) instead of O(V log V)
33. **Warm-up inference** in `configureLocaleAssets` — dummy inference through each freshly-loaded model

## Tier S — 13 KeyboardView/ShadeService bug fixes

34. **`drawOuterRoundedKey` ignores parameters** — now uses `radius` for 3 corners and `outerRadius` for only the corner specified by `which` (`KeyboardView.kt:525`)
35. **`trackpadStep = 22f` raw pixels** — changed to `dp(7f)` via `by lazy` (density-aware)
36. **`slidePath.removeAt(0)` O(N)** — switched `ArrayList` to `ArrayDeque`, `addLast`/`removeFirst` are O(1)
37. **`hit()` allocates per touch event** — replaced `minByOrNull` + `filter` chain with manual `for` loops (no allocation)
38. **`AudioManager` fetched per keypress** — cached as `by lazy` field
39. **`bgBitmap` never recycled** — setter now recycles old bitmap; `onDetachedFromWindow` recycles too
40. **Image paste pushes fake "1 char" undo** — removed `pushUndo(1, "")` for image commits (backspace was deleting wrong character)
41. **`onUpdateSelection` clears local state before IC** — reversed order: `finishComposingText()` first, then `composing.clear()`
42. **`onConfigurationChanged` doesn't re-apply prefs** — now detects orientation change and calls `applyPrefs()`
43. **`centerBest` empty-string sentinel** — size-1 case now returns `l` instead of `listOf("", l[0])`
44. **`onCursorMove` sends N key events without batch** — wrapped in `beginBatchEdit`/`endBatchEdit`; added `updateShiftFromCaps()` + `updateSuggestions()`

## Privacy & Security (7 fixes)

45. **Encrypt `BigramModel.ngram.json` at rest** — AndroidKeyStore AES-256-GCM (key alias `shade_bigram_key`); falls back to plaintext if key unavailable
46. **Encrypt `WordDictionary.user` prefs keys** — typed words are encrypted before being used as SharedPreferences keys (key alias `shade_user_words_key`)
47. **Encrypt `Backup.export` with passphrase** — PBKDF2 (210k iterations) → AES-256-GCM; `"SHD1"` magic header; backward compat with plaintext v1/v2 backups
48. **Disclose `device_id`** — updated `pref_contribute_sum` string to mention the stable per-install device ID (SHA-256 hash of random UUID)
49. **Pinned clipboard images to `filesDir`** — moved from `cacheDir` so OS can't evict pinned items under storage pressure
50. **Gate `TypingStatsStore.recordWord` on `!fieldSecureInput`** — verified `personalLearningAllowed()` already includes `!fieldSecureInput`; all per-component helpers inherit this gate
51. **`networkSecurityConfig.xml`** — `cleartextTrafficPermitted="false"`, system CAs only (defense in depth, even though no INTERNET permission)

## Feature upgrades (4 features)

52. **Voice input UI** — wired up `onTool("voice")` to call `voice?.start()`; added `voice_input` toggle in `screen_typing`; added "Voice" to `tool_entries`/`tool_values` arrays
53. **Emoji panel overhaul** — added Recent category (persisted to prefs, max 24), search bar (filters by keyword), skin-tone variants (long-press popup with 5 Fitzpatrick modifiers)
54. **Clipboard suggestion strip** — when suggestion bar is empty and user has a recent clip (< 30 chars), shows the clip text as the only candidate; cleared when user starts typing
55. **Onboarding improvements** — added "Try the keyboard" button that focuses the test field + opens IME picker; prominent "Set as default" CTA (amber background) when Shade is enabled but not default

## Cleanup

56. **Deleted 16 dead `emoji_expression/` assets** — ~2 MB APK reduction (files never opened by any code)
57. **Added `androidTest` deps** — `androidx.test.ext:junit`, `androidx.test:runner`, `androidx.test:core` + `testInstrumentationRunner`

## Files modified/created (35 total)

### Kotlin (24 files)
- `ShadeService.kt` (P0-P5 + Tier S + Feature 21/23)
- `KeyboardView.kt` (Tier S #34-39)
- `Clipboard.kt` (P0-1)
- `BigramModel.kt` (Privacy #45 — encryption)
- `WordDictionary.kt` (Privacy #46 — encryption)
- `Backup.kt` (P4 #27 + Privacy #47 — passphrase encryption)
- `EmojiPanel.kt` (Feature #53 — overhaul)
- `MainActivity.kt` (Feature #55 — onboarding)
- `SettingsActivity.kt` (P4 #25-26)
- `LearningDataCleaner.kt` (NEW — P4 #26)
- `engine/AssetMmap.kt` (NEW — P5 #30)
- `engine/AdaptiveWordMemory.kt` (P2 #11 + P0-2 shutdown)
- `engine/AppContextModel.kt` (P2 #16 + P0-2 shutdown)
- `engine/AutocorrectSuppressor.kt` (P2 #12-13 + P0-3 clear)
- `engine/ConfidenceCalibrator.kt` (P0-3 + P0-7 split API)
- `engine/OnlineRankerTrainer.kt` (P2 #17 — L2 + schema)
- `engine/TfliteCandidateRankingScorer.kt` (P3 #21 + P5 #30)
- `engine/TfliteContextEncoder.kt` (P5 #30 — mmap)
- `engine/TfliteExpressionScorer.kt` (P5 #30 — mmap)
- `engine/TfliteEmotionScorer.kt` (P5 #30 — mmap)
- `engine/CandidateRankingScorer.kt` (P3 #21 — scoreBatch interface)
- `ai/OnnxNeuralPredictor.kt` (P5 #30-31 — mmap + ORT_ENABLE_ALL)
- `ai/OnnxNwpPredictor.kt` (P5 #30-31)
- `ai/NeuralVocabulary.kt` (P5 #32 — bounded heap topK)

### Resources (8 files)
- `res/xml/method.xml` (P4 #23 — subtypes)
- `res/xml/preferences.xml` (P4 #24 — screen_learning + voice toggle)
- `res/xml/network_security_config.xml` (NEW — Privacy #51)
- `res/layout/activity_settings.xml` (P4 #25 — row_colors + row_learning)
- `res/layout/activity_main.xml` (Feature #55 — btn_try)
- `res/values/strings.xml` (P4 #24 + Privacy #48 + Features #52-55)
- `res/values/colors.xml` (Feature #55 — settings_accent)
- `res/drawable/bg_app_button_urgent.xml` (NEW — Feature #55)

### Config (2 files)
- `app/build.gradle.kts` (P5 #29 + androidTest deps)
- `app/src/main/AndroidManifest.xml` (Privacy #51 — networkSecurityConfig)

### Assets deleted (16 files)
- 16 dead `emoji_expression/` assets (~2 MB)

## What's still NOT done (truly deferred)

- Hindi/Hinglish UI translations for settings strings
- `TypingHeatmapView` still hardcodes QWERTY layout
- App theme hardcodes black (defeats `Theme.AppCompat.DayNight`)
- T6/T7/T8/T9 (model retraining work)
- `ShadeService.kt` god class split (2,300+ lines → multiple coordinators)
- Full `AccessibilityNodeProvider` for TalkBack (Tier C from the upgrade list)

These are nice-to-haves and don't block shipping.

## Build & verify

```bash
unzip Shade-TheKeyboard-FINAL.zip -d Shade-TheKeyboard-FINAL
cd Shade-TheKeyboard-FINAL
./gradlew test
./gradlew assembleDebug
```

---

# Neural Intelligence Tier — 23 helpers + A1/A2/A6/A7 + Wiring

This build adds the missing 23 neural-intelligence helper files, applies the
A1/A2 WordDictionary fixes, A6/A7 ShadeService fixes, and wires every helper
into the live suggestion pipeline.

## A1 — WordDictionary QWERTY-neighbor correction scan

`WordDictionary.correction(word)` previously only searched the typed first
letter's prefix bucket. A typical QWERTY slip (typing 'v' for 'b') would
silently fail because the candidate lives in the 'b' bucket. Added
`qwertyNeighbors(c: String): List<String>` and modified `correction()` to
scan the typed bucket PLUS neighbor buckets, with a 0.5f penalty applied
to neighbor-bucket candidates so they only win when the typed bucket
yields nothing better.

## A2 — WordDictionary locale-gated `i → I`

`correction(word)` → `correction(word, isEnglishLocale: Boolean = true)`,
`match(typed, w)` → `match(typed, w, isEnglishLocale: Boolean = true)`.
The `i → I` capitalization in `match()` is now gated on
`isEnglishLocale == true` so Romanized Hindi / Hinglish typing preserves a
literal 'i'. The call site in `ShadeService.endWord()` passes
`isEnglishLocale()`.

## A6 — Removed 4-second timer on correction revert

The 4-second timer in `tryRevertLastCorrectionEvent()` and the matching
guard in `tryDeleteCorrectionSeparator()` were overly strict: a user who
paused before backspace would silently fail to revert. Removed both
guards — a backspace inside the corrected word region always reverts now.

## A7 — Autocorrect confidence gate

Added `AUTOCORRECT_CONFIDENCE_THRESHOLD = 0.85f` to `ShadeService`'s
companion object. In `endWord()` the autocorrect path now:

1. Pulls the candidate's raw ranker score from `lastRawRankerScores` (0f
   if not ranked yet).
2. Computes a typing-speed-driven threshold adjustment via
   `TypingSpeedAdapter.confidenceThresholdAdjustment()` (range
   roughly [-0.05, +0.05]).
3. Calibrates the raw score via `confidenceCalibrator.calibrated()`.
4. Commits the correction only when the calibrated score ≥ effective
   threshold (clamped to [0.5, 0.99]).

This makes autocorrect stricter when the user is typing fast (typo-prone)
and more permissive when typing slowly (deliberate).

## 23 new helper files

### Engine package (`com.noircipher.shade.engine`) — 21 files

1. **AbbreviationExpander.kt** — `expand(typed): String?` with ~50
   abbreviations (btw → by the way, omw → on my way, idk → I don't know,
   tbh → to be honest, asap → as soon as possible, brb → be right back,
   ttyl → talk to you later, np → no problem, yw → you're welcome, thx
   → thanks, pls → please, ...). Capitalization preserved.
2. **SlangDetector.kt** — `shouldNotAutocorrect(word): Boolean` with ~80
   slang words (gonna, wanna, gotta, kinda, cuz, tho, yeah, nope, lol,
   lmao, omg, brb, afk, tbh, smh, ikr, fomo, yolo, ...). Also catches
   repeated-letter emphasis ("soooo", "nooo", "ahhh", "lollll").
3. **PhraseCompletion.kt** — `suggestPhraseCompletions(prevWords,
   typedPrefix, limit = 3): List<String>` with ~60 bigram phrase patterns
   (good → morning/luck/night, thank → you, how → are/do/does, let →
   me/us, i'll → be/let/see, ...).
4. **MultiWordCorrector.kt** — `correct(typed, isDictionaryWord = false):
   String?` for missing-space mistakes (alot → a lot, aswell → as well,
   inspite → in spite, incase → in case, atleast → at least, eachother
   → each other, ofcourse → of course, ...).
5. **SmartResponseComposer.kt** — `suggest(typed, limit = 3):
   List<String>` with response templates (ok → ok sounds good, thanks →
   thanks I appreciate it, sure → sure sounds good, hello → hello how
   are you, ...).
6. **UserVocabularyInjector.kt** — `inject(neuralPredictions, userWords,
   prefix, limit): List<Pair<String, Float>>` that merges user-learned
   words into neural predictions with a sublinear boost.
7. **CrossSentenceContext.kt** — `extendContext()`,
   `isSentenceStart()`, `shouldCapitalize()`, `sentenceStarters(prefix,
   limit = 3)` for sentence-start detection and starter suggestions.
8. **SessionBigramBoost.kt** — class with `observe(word)`,
   `observeBigram(prev, word)`, `score(word): Float`,
   `bigramScore(prev, word): Float`, `clear()` using
   `ConcurrentHashMap`. Session-scoped bigram co-occurrence counts.
9. **MorphologicalDiversity.kt** — `deduplicate(candidates, limit):
   List<String>` strips common English suffixes (ing/ed/es/s/er/est/...)
   and collapses candidates sharing the same root.
10. **SentencePositionScorer.kt** — class(context) with
    `currentPosition(contextWords): Position`, `recordAcceptance(pos)`,
    `recordShown(pos)`, `acceptanceRate(pos): Float` using
    SharedPreferences. Tracks acceptance rates per sentence position
    (START / MIDDLE / END).
11. **SuggestionRejectionTracker.kt** — class(context) with
    `recordRejection(word)`, `shouldSuppress(word): Boolean`,
    `filter(candidates): List<String>`, `clearSession()` using
    `ConcurrentHashMap`. Suppresses candidates the user has rejected
    ≥3 times in the current session.
12. **EmojiContextPredictor.kt** — `suggest(prevWord, limit = 3):
    List<String>` mapping ~100 words to emojis (love → ❤️, happy → 😊,
    sad → 😢, food → 🍕, ...).
13. **GreetingTimeBias.kt** — `timeAwareWords(prefix): List<String>`
    that suggests time-appropriate words (morning → coffee/commute,
    evening → dinner/movie, night → sleep/rest, ...).
14. **TypingSpeedAdapter.kt** — object with `onKeystroke()`,
    `isFastTyping(): Boolean`, `isSlowTyping(): Boolean`,
    `confidenceThresholdAdjustment(): Float` tracking keystroke intervals
    over a sliding window of 8 samples.
15. **AcceptanceRateAdapter.kt** — class(context) with `recordShown()`,
    `recordAccepted()`, `acceptanceRate(): Float`,
    `learningRateMultiplier(): Float` using SharedPreferences. Maps
    acceptance rate to a learning-rate multiplier in [0.5, 2.0].
16. **PerAppVocabularySnapshot.kt** — class(appContextModel) with
    `onEnterApp(appHash)`, `topWords(): List<String>`, `wordsForPrefix(prefix,
    limit = 5): List<String>`, `score(candidate): Float`. Backed by
    `AppContextModel.topWordsForCurrentApp()` (newly exposed).
17. **SuggestionCache.kt** — object with `get(prev2, prev1, prefix):
    List<String>?`, `put(prev2, prev1, prefix, candidates)`,
    `invalidate()`, `invalidatePrefix(prefix)` using ConcurrentHashMap
    with a 1500ms TTL and 256-entry cap.
18. **ConfusablePairs.kt** — object with `correct(typed, prevWord):
    String?` and `isConfusable(typed): Boolean` for there/their/they're,
    your/you're, its/it's, to/too/two, then/than, affect/effect,
    accept/except, ... Each rule can carry context-trigger and
    context-block word sets.
19. **PhoneticMatcher.kt** — object with `soundex(word): String`,
    `phoneticallySimilar(a, b): Boolean`, `findPhoneticMatches(typed,
    dictionary, limit = 5): List<String>`. Standard Soundex.
20. **PunctuationPredictor.kt** — object with
    `suggestPunctuation(contextWords): String?` (Hello → comma, What →
    question mark, Thanks → period, Wow → exclamation, ...).
21. **SentenceCompleter.kt** — object with `suggest(contextWords, limit
    = 3): List<String>` for multi-word sentence completions (How are you
    → doing today?, I'll be there → in 20 minutes, Let me know → if
    you need anything, ...).

### Main package (`com.noircipher.shade`) — 2 files

22. **FieldTypeDetector.kt** — object with `detect(editorInfo):
    FieldType`, `shouldShowSuggestions(type)`,
    `shouldAutocorrect(type)`, `shouldSmartCompose(type)`,
    `suggestionLimit(type)`. Classifies fields into NORMAL_TEXT /
    PASSWORD / EMAIL / URL / NUMBER / PHONE / DATETIME / SEARCH /
    NO_SUGGESTIONS based on InputType + variation.
23. **UrlEmailDetector.kt** — object with `detect(composingText,
    beforeCursor): Detection`, `suppressAutocorrect(detection)`,
    `suppressSuggestions(detection)` using Regex patterns. Detects
    email, URL (scheme and bare-domain), phone number, and URL path
    segment — any of which suppresses autocorrect / suggestions.

## UserWordMemory.kt — added `snapshot()`

Added `@Synchronized fun snapshot(): Map<String, Float>` returning all
user words with positive scores (decrypted from prefs keys, capped at
32 chars, scored in [0, 1]) as a fresh HashMap copy.

## AppContextModel.kt — exposed `topWordsForCurrentApp()`

Added `@Synchronized fun topWordsForCurrentApp(prefix: String, limit:
Int): List<String>` that returns highest-scoring words for the current
app whose lowercase form starts with `prefix`. Used by
`PerAppVocabularySnapshot` for prefix lookups.

## OnlineRankerTrainer.kt — added acceptance-rate hooks

Added `setLearningRateMultiplier(mult: Float)` (persisted in prefs,
clamped to [0.5, 2.0]) and `shouldExplore(): Boolean` (multi-armed
bandit exploration, ~5% rate via PRNG seeded by nanoTime XOR thread id).
The `adapt()` method now multiplies its base learning rate by the
stored multiplier so high-acceptance users learn faster, low-acceptance
users learn slower.

## ShadeService.kt — full wiring (4a-4p)

### 4a — Lazy field declarations (5 new fields)
`sessionBigramBoost`, `sentencePositionScorer`, `rejectionTracker`,
`acceptanceRateAdapter`, `perAppVocabSnapshot` — all `by lazy`,
initialized on first use.

### 4b — `currentFieldType` field
`private var currentFieldType: FieldTypeDetector.FieldType =
FieldTypeDetector.FieldType.NORMAL_TEXT` — initialized to a safe
default and updated in `onStartInputView`.

### 4c — Field-type detection in `onStartInputView`
After computing privacy flags: `currentFieldType =
FieldTypeDetector.detect(editorInfo)`. Also mirrors app-context state
into `perAppVocabSnapshot.onEnterApp(...)` so its prefix lookups
operate against the right app.

### 4d — `TypingSpeedAdapter.onKeystroke()` in `onText()`
Called at the very top of `onText()` (before any character dispatch)
to track keystroke intervals.

### 4e — Field-type + URL/email gates in `updateSuggestions()`
- After `suggestionsOn`/`suggestionsAllowed`: returns empty if
  `!FieldTypeDetector.shouldShowSuggestions(currentFieldType)`.
- After computing `typedWord`: runs `UrlEmailDetector.detect()` and
  returns empty if `UrlEmailDetector.suppressSuggestions()`.

### 4f — `endWord()` autocorrect pipeline overhaul
- URL/email detection on the original typed word — suppresses
  autocorrect mid-token.
- `SlangDetector.shouldNotAutocorrect(w)` → `effectiveShouldCorrect`.
- `AbbreviationExpander.expand(w)` when `effectiveShouldCorrect`.
- `MultiWordCorrector.correct(w, isDictionaryWord = dict.contains(w))`
  when `effectiveShouldCorrect && abbrevExpansion == null`.
- `corrected` is now a `when` block:
  - `abbrevExpansion != null → abbrevExpansion`
  - `multiWordCorrection != null → multiWordCorrection`
  - `!effectiveShouldCorrect → null`
  - else → dict.correction + suppressor check + confidence gate (A7).
- `dict.correction(w, isEnglishLocale())` (A2).

### 4g — Post-commit observations in `endWord()`
After `ic?.commitText(finalWord, 1)` and `composing.clear()`:
- `sessionBigramBoost.observe(finalWord)`.
- For each shown candidate that wasn't picked (i.e. `finalWord` isn't
  in `lastShownCandidates`), call `rejectionTracker.recordRejection(...)`
  on up to 3 prior candidates.
- If `personalLearningAllowed()`: compute the post-commit sentence
  position and call `sentencePositionScorer.recordShown(pos)`.

### 4h — Acceptance-rate tracking in `setCandidates()`
After the existing `insightsAllowed` check, if
`personalLearningAllowed()` and `effective.isNotEmpty()`:
`acceptanceRateAdapter.recordShown()`.

### 4i — Acceptance recording in `pickSuggestion()`
After `typingStatsStore.recordSuggestionAccepted()`:
- If `personalLearningAllowed()`: `acceptanceRateAdapter.recordAccepted()`
  + `onlineRankerTrainer.setLearningRateMultiplier(acceptanceRateAdapter.learningRateMultiplier())`.
- `sessionBigramBoost.observe(w)`.
- Compute sentence position from `contextWordsExcluding(w)`.
- If `personalLearningAllowed()`: `sentencePositionScorer.recordAcceptance(pos)`.

### 4j — Typed-word pipeline injections (12 helpers)
In `updateSuggestions()` after `learnedRankCandidates` returns and
before the `when` block that builds `shown`:
1. `PhraseCompletion.suggestPhraseCompletions(...)` — multi-word phrase
   completion prepended to ranked.
2. `SmartResponseComposer.suggest(...)` (when
   `FieldTypeDetector.shouldSmartCompose(currentFieldType)`).
3. `CrossSentenceContext.extendContext()` + sentence-start check →
   `sentenceStarters(...)`.
4. `GreetingTimeBias.timeAwareWords(prefix)`.
5. `suggestNumbersForContext(...)` — smart number prediction.
6. `EmojiContextPredictor.suggest(prev1, limit = 2)`.
7. `rejectionTracker.filter(ranked)` — drop recently-rejected.
8. `MorphologicalDiversity.deduplicate(ranked, limit = 6)`.
9. `perAppVocabSnapshot.wordsForPrefix(prefix, limit = 2)`.
10. `ConfusablePairs.correct(prefix, prev1)`.
11. `PhoneticMatcher.findPhoneticMatches(prefix, dict.suggest(...), 2)`.
12. `PunctuationPredictor.suggestPunctuation(contextWords)`.
13. `SentenceCompleter.suggest(contextWords, limit = 2)`.
14. `SuggestionCache.put(prev2, prev1, prefix, ranked)`.
15. Multi-armed bandit exploration: `onlineRankerTrainer.shouldExplore()`
    occasionally swaps top-2 to surface a less-likely candidate.

### 4k — User vocabulary injection in neural prediction path
`rawPredictions = neural?.predictNext(neuralCtx, effectivePrefix, 24)`
returns `List<Pair<String, Float>>`. If
`effectivePrefix.isNotEmpty() && personalLearningAllowed()`, the
predictions are merged with `userWordMemory.snapshot()` (filtered by
prefix) via `UserVocabularyInjector.inject(...)`. The injected list
replaces `rawPredictions` for downstream stages.

### 4l — Session bigram boost in `learnedRankCandidates()`
In the `rescored` mapIndexed block, each candidate's `combined` score
gets `+ sessionBoost` where:
```kotlin
val sessionBoost = sessionBigramBoost.score(candidate) * 0.06f +
    sessionBigramBoost.bigramScore(contextWords.lastOrNull(), candidate) * 0.04f
```

### 4m — `suggestNumbersForContext` helper
New private method on `ShadeService` placed near `centerBest`:
returns short numeric completions after "in" (5/10/15/20/30), "costs" /
"cost" / "price" / "pay" / "paid" ($), "at" / "around" / "about" (5/10/20/50),
"number" / "no." (1/2/3). Returns at most 2 candidates matching the
typed prefix.

### 4n — A6: removed 4-second timer
- `tryRevertLastCorrectionEvent()`: removed the
  `if (SystemClock.uptimeMillis() - event.timestamp > 4000L) { ...; return false }`
  block.
- `tryDeleteCorrectionSeparator()`: removed the
  `if (SystemClock.uptimeMillis() - event.timestamp > 4000L) return false`
  line.

### 4o — `AUTOCORRECT_CONFIDENCE_THRESHOLD` constant
Added to `ShadeService.companion`:
```kotlin
private const val AUTOCORRECT_CONFIDENCE_THRESHOLD = 0.85f
```
Used by the A7 confidence gate in `endWord()`.

### 4p — Orientation change handler
Already present from Tier S #42 — `onConfigurationChanged` detects
orientation change and calls `applyPrefs()`. No change needed.

### 4-extra — `SuggestionCache.get()` short-circuit
At the start of `suggestJob` (after the debounce delay), check
`SuggestionCache.get(prev2, prev1, prefix)` — if cached, post
`setCandidates(centerBest(cached.take(3)))` and return early without
running the full pipeline.

## Verification

- All 23 new helper files exist (21 in `engine/`, 2 in main package).
- All 23 helpers are referenced from `ShadeService.kt`.
- Brace / paren / bracket balance verified on all modified files.
- No existing tests reference the changed APIs (`correction`,
  `correctionAsync`, `qwertyNeighbors`, `snapshot`).
- `OnlineRankerTrainer.adapt()` still uses default `learningRate =
  0.03f` and just multiplies it by the new multiplier — existing
  callers see no behavior change unless they pass a multiplier.



---

# Audit follow-up fixes

1. **Learned-word storage rewritten.** The old store used random-IV AES-GCM ciphertext as the
   SharedPreferences *key*, so every learn() created a new entry (unbounded growth, scores never
   accumulated, Keystore call on the typing path). Words now live in one Keystore-encrypted file
   (`user_words.enc`, `EncryptedMapFile`), saved on a background thread with a 2 s debounce. Legacy data
   is migrated once (`UserWordCodec.fromLegacyEntries`, duplicates collapse to the max score).
2. **Backup / export fixed.** Backups (schema v3) store plain words inside the passphrase-encrypted file,
   so they restore on a new phone; v1/v2 backups are decoded when the key is readable and dropped otherwise.
   `TrainingExport` read ciphertext keys (dropped by its own filter), copied the n-gram file undecrypted,
   and closed the zip stream while writing the word list, so exports never worked. All three are fixed.
3. **No plaintext fallback.** `BigramModel` / learned words fail closed when encryption is unavailable.
   `UserWordMemory` (previously plaintext `w:<word>` prefs) is now encrypted and is cleared by
   "Clear learned data".
4. **Honest labels.** The "Contribute anonymized typing data" toggle is now "Keep local learning
   snapshots", the per-install device ID was removed, and voice typing is disclosed as using the system
   recognizer (which may use the network). Voice now prefers offline recognition and sets the language.
5. **Release signing** no longer falls back to the debug keystore; without `keystore.properties` the release
   build is unsigned.
6. **16 KB page size:** ONNX Runtime 1.17.3 -> 1.23.2 and TensorFlow Lite 2.16.1 -> LiteRT 1.4.0
   (`com.google.ai.edge.litert`). Needs an on-device regression test of every model.
7. **Main-thread work:** `onDestroy()` no longer saves n-grams on the main thread.
8. **CI:** `ci.yml` + `build.yml` merged into one workflow (tests, lint, debug build); least-privilege token.
9. **ProGuard:** `Log.w` / `Log.e` are no longer stripped from release builds.
10. **Tests:** added `UserWordCodecTest` (round trip, legacy migration regression, untrusted input).
    Removed unused `androidTest` dependencies.


## Startup-speed pass
- SecureStore / ClipboardStore: Keystore key is cached in memory (was looked up on every decrypt).
- ClipboardStore.load(): parsed clips are cached until the stored data changes (the clipboard chip no longer decrypts every clip each time the keyboard opens).
- BigramModel loading and clipboard cleanup moved off the main thread (ShadeService.onCreate).
- Sensitive-text regex compiled once.
- Added baseline profile + androidx.profileinstaller.
- KeyboardView: requestUnbufferedDispatch on touch-down (lower key latency).
- ShadeService: per-app helper objects pre-built in the background.

## Settings redesign + feel pass
- Settings sub-screens restyled as pitch-black monochrome tiles with section headers (pref_row / pref_seekbar_row / pref_category layouts); every preference key and default is unchanged.
- Voice and Emoji rows now open real screens (voice_input, emoji_suggestions moved there from Typing).
- Removed two dead rows (import/delete dictionary) that had no handler.
- values-v35 theme was replacing the black/white theme with a blue one on Android 15+; now identical to the base theme plus the edge-to-edge opt-out.
- Added system-bar inset padding to SettingsActivity.
- New "Vibration strength" setting (default Medium = previous behaviour) + VIBRATE permission.
- Key press / popup timings made snappier (30 / 55 / 60 ms).
