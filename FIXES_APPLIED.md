# Shade Keyboard — Complete Fix History

This document lists every fix applied to this build. The build covers all six
phases of work: new helper files, engine modifications, the ShadeService.kt
integration, KeyboardView.kt polish, resource updates, and final packaging.

## Phase 1 — New engine / shade helper files (25 files)

All 25 helper files are created under `app/src/main/kotlin/com/noircipher/shade/engine/`
(or `.../shade/` for the two IME-level detectors). They are small, focused,
unit-testable objects with no Android framework dependencies (except the few
that legitimately need a `Context` for SharedPreferences).

1. **AssetMmap.kt** — memory-mapped model loading via `MappedByteBuffer`,
   falls back to `readBytes()` if the asset is compressed.
2. **AbbreviationExpander.kt** — 80+ abbreviation → full-form expansions
   (`btw` → `by the way`, `omw` → `on my way`, …). Capitalisation is preserved.
3. **SlangDetector.kt** — 80+ slang / colloquial tokens (`gonna`, `wanna`,
   `cuz`, `tho`, `lol`, …) that should NOT be autocorrected.
4. **PhraseCompletion.kt** — 60+ bigram phrase-completion patterns
   (`as a` → `matter`, `thank` → `you`, …).
5. **ConfusablePairs.kt** — there/their/they're, your/you're, its/it's,
   to/too/two, then/than, affect/effect, loose/lose, whose/who's, were/where.
   The right homophone is chosen by looking at the previous word.
6. **PhoneticMatcher.kt** — Soundex-like 4-character code; `similarity()`
   returns a [0,1] score for phonetic matching (e.g. `nite` ≈ `knight`).
7. **MultiWordCorrector.kt** — 50+ smushed-phrase corrections
   (`alot` → `a lot`, `aswell` → `as well`, `inspite` → `in spite`, …).
8. **SmartResponseComposer.kt** — 40+ trigger → response templates
   (`ok` → `ok, sounds good`, `thanks` → `thanks, I appreciate it`, …).
9. **PunctuationPredictor.kt** — predicts next punctuation from the previous
   token (after `Hello` → `,`, after `What` → `?`, after `thanks` → `.`).
10. **SentenceCompleter.kt** — 60+ multi-token sentence completion templates
    (`How are you` → `doing today?`, `I'll be there` → `in 20 minutes`, …).
11. **UserVocabularyInjector.kt** — merges user-learned words into a list of
    neural predictions, boosting existing entries and inserting new ones.
12. **CrossSentenceContext.kt** — carries the last 1–2 words across sentence
    boundaries; `sentenceStarters()` returns context-appropriate first words.
13. **SessionBigramBoost.kt** — session-scoped word / bigram counts (cleared
    on service destroy). `score(prev, word)` returns a [0,1] boost.
14. **MorphologicalDiversity.kt** — light Porter-style stemmer; `deduplicate()`
    collapses "type"/"types"/"typed" to a single entry.
15. **SentencePositionScorer.kt** — per-position (beginning/middle/end)
    acceptance-rate tracking; persisted to SharedPreferences.
16. **SuggestionRejectionTracker.kt** — suppresses words the user has rejected
    3+ times in the last 24h; clears the rejection count on acceptance.
17. **EmojiContextPredictor.kt** — 100+ word → emoji mappings
    (`love` → ❤️, `happy` → 😊, `pizza` → 🍕, …).
18. **GreetingTimeBias.kt** — time-of-day-aware greeting words
    (morning → `good morning`, evening → `good evening`, …).
19. **TypingSpeedAdapter.kt** — tracks inter-keystroke intervals;
    `confidenceThresholdAdjustment()` returns 1.2× for fast typers, 0.85×
    for slow typers, 1.0× otherwise.
20. **AcceptanceRateAdapter.kt** — acceptance-rate counter; when the user
    accepts ≥60% of suggestions, the ranker learning rate is 1.5×; when ≤20%,
    it's 0.5×.
21. **PerAppVocabularySnapshot.kt** — small LRU cache of the top-N words per
    app, pre-loaded from `AppContextModel.topWords()` for fast prefix lookup.
22. **SuggestionCache.kt** — small LRU cache (32 entries) of suggestion-bar
    results keyed by (prefix, prev1, prev2, locale, packageHash).
23. **FieldTypeDetector.kt** (under `shade/`) — detects TEXT / EMAIL / URL /
    PHONE / NUMBER / DATE / SEARCH / PASSWORD / CODE / MENTION / MESSAGE /
    ADDRESS / UNKNOWN from an `EditorInfo`.
24. **UrlEmailDetector.kt** (under `shade/`) — detects URL / email / path /
    hashtag / @-mention in the composing text; returns whether autocorrect
    and/or suggestions should be suppressed.
25. **LearningDataCleaner.kt** (under `shade/`) — centralised clear-all /
    clear-one for the 5 adaptive-learning SharedPreferences stores.

## Phase 2 — Engine modifications (14 files)

### AdaptiveWordMemory.kt
- Boost is **always ≥ 1** (never decreases score on use).
- Decay applied only at read time in `score()`.
- `score()` normalised to [0,1] by dividing by `MAX_SCORE = 80`.
- Added `shutdown()` (flush + cancel scope) — `clear()` is for user-initiated
  reset only.

### AutocorrectSuppressor.kt
- The `(prev, typed, corrected)` key uses a NUL separator (`"\u0000"`) so
  `("a","bc","d")` and `("ab","c","d")` no longer collide.
- Switched to access-ordered `LinkedHashMap(16, 0.75f, true)` for proper LRU.
- Added `clear()` for the user-facing "Reset learned data" action.

### ConfidenceCalibrator.kt
- Split `recordPrediction` into `recordShown(raw)` and `recordAcceptance(raw)`
  so the "shown" denominator isn't inflated 8× per keystroke.
- Added `clear()`.

### AppContextModel.kt
- `currentHash` is `@Volatile` (was a torn-read hazard).
- `apps.size` capped at `MAX_APPS = 50`.
- `score()` normalised to [0,1] by dividing by `MAX_WORD_SCORE = 100`.
- Added `shutdown()` / `flush()` for graceful service teardown.
- Cached `MessageDigest` instance (`by lazy`).
- Added `topWords(limit)` and `currentPackageHash()` for the per-app snapshot.

### OnlineRankerTrainer.kt
- L2 regularisation on every step (`L2_LAMBDA = 1e-4f`).
- Schema-version tag on persisted weights (`SCHEMA_VERSION = 1`).
- `score()` returns `sigmoid(z) ∈ [0,1]`.
- Added `DECAY_FACTOR = 0.999f`, applied in `adapt()` so weights slowly drift
  toward zero between updates (prevents stale weights from dominating).
- Added `shouldExplore()` (5% exploration rate) for the ε-greedy bandit.
- Added `setLearningRateMultiplier(m)` — driven by `AcceptanceRateAdapter`:
  when the user accepts suggestions frequently, the multiplier is > 1 (faster
  learning); when rarely, < 1 (slower, more cautious updates).

### TfliteCandidateRankingScorer.kt
- Model loaded via `AssetMmap` (memory-mapped, no heap-doubling `readBytes()`).
- Reuses input/output buffers across calls (`System.arraycopy` instead of
  `copyOf`).
- Caches the resized batch shape in `maxBatchSize` (resize happens at most
  once per batch size).
- Added `scoreBatch()` with `tryResizeBatch()` and per-batch input/output
  buffer caches.

### TfliteContextEncoder.kt / TfliteExpressionScorer.kt / TfliteEmotionScorer.kt
- All three now load their model via `AssetMmap`.
- `TfliteExpressionScorer` and `TfliteEmotionScorer` had their
  `ByteBuffer` / `ByteOrder` imports removed (no longer needed).

### CandidateRankingScorer.kt
- Added `scoreBatch(batch: List<FloatArray>): FloatArray` to the interface.

### OnnxNeuralPredictor.kt / OnnxNwpPredictor.kt
- Both load their model via `AssetMmap` with a `readBytes()` fallback for
  compressed assets.
- Both set `ORT_ENABLE_ALL` optimisation level.
- Both set 2 intra-op threads.

### NeuralVocabulary.kt
- `topK()` rewritten with a bounded `PriorityQueue` — O(V log limit) instead
  of O(V log V). The queue holds at most `limit` entries; new candidates only
  displace the current minimum.

### BigramModel.kt
- `ngram.json` is now encrypted at rest with AndroidKeyStore AES-256-GCM
  (key alias `shade_bigram_key`).
- Falls back to plaintext if the key is unavailable (e.g. keystore wiped).
- On load failure, the unreadable file is renamed to `ngram.json.bad` rather
  than silently overwritten on the next save.

### WordDictionary.kt
- `user_words` prefs keys are encrypted with AndroidKeyStore AES-256-GCM
  (key alias `shade_user_words_key`) before being used as SharedPreferences
  keys — plaintext typed words no longer appear on disk.
- Added `qwertyNeighbors(c: Char): List<Char>` helper that returns the
  immediate QWERTY neighbours of a letter.
- `correction(word, isEnglishLocale = true)` now takes a locale parameter;
  when no candidate matches the strict-prefix bucket, it relaxes to a
  QWERTY-neighbour first-letter search.
- `match(typed, w, isEnglishLocale = true)` gates the `i → I` autocorrect on
  `isEnglishLocale` — `i` is a valid lowercase word in Hindi, Italian, etc.
- `reloadUser()` now accepts `Float`, `Int`, and `Long` user-word scores
  (fixes the silent-drop bug in older v1 backups).

### Backup.kt
- Passphrase-based encryption: PBKDF2-HMAC-SHA256 (210 000 iterations) →
  AES-256-GCM.
- `"SHD1"` magic header; plaintext JSON backups are no longer accepted.
- Includes the 5 adaptive prefs stores (`shade_adaptive_words`,
  `shade_app_context`, `shade_autocorrect_suppressor`,
  `shade_confidence_calibrator`, `shade_online_ranker`) in addition to the
  legacy `user_words` store.
- Float/Int silent-drop bug on `user_words` fixed — all numeric types are
  round-tripped through `Double` to preserve precision.

### UserWordMemory.kt
- Added `snapshot(): Map<String, Float>` returning all known user-word scores
  in [0,1] — used by `UserVocabularyInjector` to merge user-learned words into
  the neural model's predictions.

## Phase 3 — ShadeService.kt integration (45+ changes)

### P0 — Compile errors + data loss (9 fixes)
1. `onDestroy`: replaced `clear()` calls on `adaptiveWordMemory` /
   `appContextModel` with `shutdown()`; removed `clear()` on the other 3
   adaptive components (they persist synchronously and don't need shutdown).
2. Restored `synchronized(neuralLock) { failedNeuralLocales.clear() }` in
   `onDestroy`.
3. `lastRankedFeatures` / `lastRawRankerScores` are `ConcurrentHashMap`;
   `lastShownCandidates` is `@Volatile`.
4. Removed `confidenceCalibrator.recordPrediction(raw, false)` from the
   per-keystroke loop in `learnedRankCandidates`; replaced with
   `confidenceCalibrator.recordShown(raw)` in `setCandidates` for top-3
   displayed candidates only.
5. Restored `isEnglishLocale()` gate on `i → I` autocorrect in `endWord()`;
   also threaded `isEnglishLocale` into `dict.correction()` and `match()`.
6. `failedNeuralLocales` uses `SystemClock.elapsedRealtime()` (monotonic) +
   `NEURAL_FAILURE_TTL_MS = 5 * 60 * 1000L` constant.
7. Restored `withContext(Dispatchers.Main.immediate)` in both suggestion
   pipelines.
8. Added `suggestDebounceJob` + `SUGGEST_DEBOUNCE_MS = 32L` debounce before
   launching the suggestion pipeline.
9. Added `lastRankedForComposing` field (A7 stale-score detection).

### P1 — Per-component privacy gating (5 toggles)
10. Added 5 learning-toggle fields: `learningAdaptiveOn`, `learningAppCtxOn`,
    `learningSuppressorOn`, `learningCalibratorOn`, `learningRankerOn`.
11. Added 5 helper methods: `adaptiveLearningAllowed()`,
    `appContextLearningAllowed()`, `suppressorLearningAllowed()`,
    `calibratorLearningAllowed()`, `rankerLearningAllowed()`.
12. Read 5 new prefs in `applyPrefs()` (`learning_adaptive`, `learning_appctx`,
    `learning_suppressor`, `learning_calibrator`, `learning_ranker`).
13. All call sites gated on the per-component helpers — user can disable just
    per-app context tracking without disabling everything.
14. Added `lastOrientation` field; `onConfigurationChanged` re-applies prefs
    on orientation change.

### P5 — Neural perf (3 fixes)
15. Wired `scorer.scoreBatch()` into `learnedRankCandidates`.
16. Added `warmUpNeuralEngines()` called at end of `configureLocaleAssets`.
17. Added `localeAssetsMutex` (Mutex) so `configureLocaleAssets` is
    serialised across coroutine calls.

### Tier S — 11 fixes
18. **#5** `isWithinEditDistance1()` helper + relaxed strict prefix filter
    in `updateSuggestions` so edit-distance-1 matches also surface.
19. **#2** `AbbreviationExpander.expand()` in `endWord()` after
    `dict.correction()`.
20. **#1-phrase** `PhraseCompletion.suggestPhraseCompletions()` in the
    typed-word pipeline.
21. **#31** `AUTOCORRECT_CONFIDENCE_THRESHOLD = 0.85f`; autocorrect in
    `endWord()` gated on `confidenceCalibrator.calibrated(raw) >= threshold`.
22. **A3** `SlangDetector.shouldNotAutocorrect()` gate in `endWord()`.
23. **A6** Removed the 4000 ms timer from `tryRevertLastCorrectionEvent` and
    `tryDeleteCorrectionSeparator` — users who switch apps mid-sentence
    shouldn't lose undo.
24. **A7** `lastRankedForComposing` tracked in `learnedRankCandidates` so
    future calls can detect stale scores.
25. **#21** `TypingSpeedAdapter.onKeystroke()` in `onText()` +
    `confidenceThresholdAdjustment()` multiplying the autocorrect threshold.
26. **ConfusablePairs** wired into `endWord()` before `dict.correction()` —
    picks the right homophone based on context.
27. **MultiWordCorrector** wired into `endWord()` before `dict.correction()`.
28. **PunctuationPredictor** wired into `updateSuggestions` when prefix is
    empty (predict next punctuation after a committed word).

### Neural intelligence (20 improvements)
29. **#1** `UserVocabularyInjector.inject()` in `learnedRankCandidates` —
    user-learned words are merged into the neural model's predictions.
30. **#2** `sessionBigramBoost.score()` + `bigramScore()` in
    `learnedRankCandidates`; `observe()` in `endWord` / `pickSuggestion`.
31. **#3** `MultiWordCorrector.correct()` in `endWord()`.
32. **#4** `FieldTypeDetector.detect()` in `onStartInputView` +
    `shouldShowSuggestions()` gate in `updateSuggestions`.
33. **#5** `MorphologicalDiversity.deduplicate()` in the typed-word pipeline.
34. **#6 + #7** `CrossSentenceContext.extendContext()` +
    `sentenceStarters()` in the typed-word pipeline.
35. **#9** `suggestNumbersForContext()` helper used in the typed-word
    pipeline.
36. **#10** `sentencePositionScorer.recordShown()` / `recordAcceptance()`
    in `endWord` / `pickSuggestion`.
37. **#11** `UrlEmailDetector.detect()` + `suppressAutocorrect` /
    `suppressSuggestions` in `updateSuggestions` + `endWord`.
38. **#14** `rejectionTracker.recordRejection()` in `endWord` (via
    `tryRevertLastCorrectionEvent`) + `filter()` in the typed-word pipeline;
    `recordAcceptance()` in `pickSuggestion`.
39. **#15** `SmartResponseComposer.suggest()` in the typed-word pipeline
    (gated on `shouldSmartCompose`).
40. **#17** `EmojiContextPredictor.suggest()` in the typed-word pipeline.
41. **#18** `GreetingTimeBias.timeAwareWords()` in the typed-word pipeline
    (when prefix is empty).
42. **#19** `acceptanceRateAdapter.recordShown()` in `setCandidates`;
    `recordAccepted()` + `setLearningRateMultiplier()` in `pickSuggestion`.
43. **#20** `perAppVocabSnapshot.activate()` in `onStartInputView` +
    `wordsForPrefix()` in the typed-word pipeline.
44. **#32** `onlineRankerTrainer.shouldExplore()` ε-greedy bandit in the
    typed-word pipeline (5% exploration rate).
45. **Phonetic matching** — when the typed-word pipeline produces < 3
    candidates and the prefix is ≥ 4 chars, surface a Soundex-similar
    candidate (e.g. `nite` → `knight`).

### Autocorrect fixes
46. **A1** QWERTY neighbour bucket search in `WordDictionary.correction()`.
47. **A2** `isEnglishLocale` gate in `WordDictionary.match()`.

### onDestroy cleanup
48. Clears `sessionBigramBoost`, `crossSentenceContext`, `typingSpeedAdapter`,
    `suggestionCache` so the next session starts fresh.

## Phase 4 — KeyboardView.kt (6 fixes)

49. `drawOuterRoundedKey` now uses `radius` for 3 corners and `outerRadius`
    for only the corner specified by `which`.
50. `trackpadStep = dp(7f)` via `by lazy` (density-aware; was raw `22f`).
51. `slidePath` switched from `ArrayList` to `ArrayDeque`;
    `addLast` / `removeFirst` are O(1).
52. `hit()` rewritten with manual `for` loops (no `minByOrNull` + `filter`
    chain allocation per touch event).
53. `AudioManager` cached as `by lazy` field (was fetched per keypress).
54. `bgBitmap` setter recycles the old bitmap on replace;
    `onDetachedFromWindow` recycles too.

## Phase 5 — Resources (10 changes)

55. `app/build.gradle.kts`: added `testInstrumentationRunner`,
    `androidResources { noCompress += listOf("ort", "tflite", "onnx") }`,
    and `androidTestImplementation` deps (`androidx.test.ext:junit`,
    `androidx.test:runner`, `androidx.test:core`).
56. `res/xml/method.xml`: added Hindi + Hinglish IME subtypes (3 total).
57. `res/values/strings.xml`: added `scr_learning`, `subtype_*`,
    `pref_learning_*`, `pref_voice_input_*`, etc. Updated
    `pref_contribute_sum` to disclose the stable per-install `device_id`.
58. `res/xml/preferences.xml`: added `screen_learning` with 5 toggles + 6
    clear buttons; added `voice_input` toggle in `screen_typing`.
59. `res/layout/activity_settings.xml`: added `row_colors` + `row_learning`.
60. `SettingsActivity.kt`: fixed `row_preferences` → `screen_feedback`;
    added `row_colors` → `screen_colors`, `row_learning` → `screen_learning`;
    added 6 clear button handlers; `clear_learned` clears `user_words` and all
    5 adaptive stores via `LearningDataCleaner.clearAll(ctx)`.
61. `AndroidManifest.xml`: added `android:networkSecurityConfig`.
62. `res/xml/network_security_config.xml`: `cleartextTrafficPermitted="false"`,
    system CAs only (defense in depth — Shade declares no INTERNET permission).
63. Deleted 12 dead `emoji_expression/` assets in a previous run; the 4
    remaining (`transformer_expression.tflite`, `qrnn_df_0.0.tflite`,
    `concept_list.tsv`, `emotion_labels.tsv`) are actively used by
    `TfliteExpressionScorer` and `TfliteEmotionScorer`.

## Phase 6 — Verification + packaging

64. Verified brace / paren balance on every modified `.kt` file.
65. Verified every new symbol created in Phase 1 is referenced in
    ShadeService.kt (AbbreviationExpander, SlangDetector, PhraseCompletion,
    ConfusablePairs, PhoneticMatcher, MultiWordCorrector,
    SmartResponseComposer, PunctuationPredictor, SentenceCompleter,
    UserVocabularyInjector, CrossSentenceContext, SessionBigramBoost,
    MorphologicalDiversity, SentencePositionScorer, SuggestionRejectionTracker,
    EmojiContextPredictor, GreetingTimeBias, TypingSpeedAdapter,
    AcceptanceRateAdapter, PerAppVocabularySnapshot, SuggestionCache,
    FieldTypeDetector, UrlEmailDetector).
66. Wrote this `FIXES_APPLIED.md`.
67. Zipped the entire project as `Shade-TheKeyboard-FINAL-fixed.zip`.

## Files modified / created (50 total)

### New Kotlin (25 files)
- `engine/AssetMmap.kt`
- `engine/AbbreviationExpander.kt`
- `engine/SlangDetector.kt`
- `engine/PhraseCompletion.kt`
- `engine/ConfusablePairs.kt`
- `engine/PhoneticMatcher.kt`
- `engine/MultiWordCorrector.kt`
- `engine/SmartResponseComposer.kt`
- `engine/PunctuationPredictor.kt`
- `engine/SentenceCompleter.kt`
- `engine/UserVocabularyInjector.kt`
- `engine/CrossSentenceContext.kt`
- `engine/SessionBigramBoost.kt`
- `engine/MorphologicalDiversity.kt`
- `engine/SentencePositionScorer.kt`
- `engine/SuggestionRejectionTracker.kt`
- `engine/EmojiContextPredictor.kt`
- `engine/GreetingTimeBias.kt`
- `engine/TypingSpeedAdapter.kt`
- `engine/AcceptanceRateAdapter.kt`
- `engine/PerAppVocabularySnapshot.kt`
- `engine/SuggestionCache.kt`
- `FieldTypeDetector.kt`
- `UrlEmailDetector.kt`
- `LearningDataCleaner.kt`

### Modified Kotlin (13 files)
- `ShadeService.kt` (Phase 3 — 45+ integration points)
- `WordDictionary.kt` (qwertyNeighbors, isEnglishLocale, encryption)
- `BigramModel.kt` (encryption at rest)
- `Backup.kt` (passphrase encryption, 5 adaptive stores)
- `SettingsActivity.kt` (row mappings, clear handlers)
- `KeyboardView.kt` (Phase 4 — 6 fixes)
- `engine/AdaptiveWordMemory.kt` (boost ≥ 1, shutdown)
- `engine/AutocorrectSuppressor.kt` (NUL separator, LRU)
- `engine/ConfidenceCalibrator.kt` (split API, clear)
- `engine/AppContextModel.kt` (@Volatile hash, MAX_APPS, topWords)
- `engine/OnlineRankerTrainer.kt` (L2, decay, shouldExplore, lrMultiplier)
- `engine/TfliteCandidateRankingScorer.kt` (AssetMmap, scoreBatch)
- `engine/TfliteContextEncoder.kt` / `TfliteExpressionScorer.kt` /
  `TfliteEmotionScorer.kt` (AssetMmap)
- `engine/CandidateRankingScorer.kt` (scoreBatch in interface)
- `engine/UserWordMemory.kt` (snapshot)
- `ai/OnnxNeuralPredictor.kt` / `ai/OnnxNwpPredictor.kt` (AssetMmap, ORT_ENABLE_ALL)
- `ai/NeuralVocabulary.kt` (bounded heap topK)

### Resources (8 files)
- `res/xml/method.xml` (Hindi + Hinglish subtypes)
- `res/xml/preferences.xml` (screen_learning + voice toggle)
- `res/xml/network_security_config.xml` (cleartext off)
- `res/layout/activity_settings.xml` (row_colors + row_learning)
- `res/values/strings.xml` (learning strings, voice strings, device_id disclosure)
- `app/build.gradle.kts` (noCompress, testInstrumentationRunner, androidTest deps)
- `app/src/main/AndroidManifest.xml` (networkSecurityConfig)

## What's still NOT done (deferred, doesn't block shipping)

- Hindi/Hinglish UI translations for the new settings strings.
- `TypingHeatmapView` still hardcodes QWERTY layout.
- App theme hardcodes black (defeats `Theme.AppCompat.DayNight`).
- T6/T7/T8/T9 (model retraining work — requires training pipeline).
- `ShadeService.kt` god class split (2 500+ lines → multiple coordinators).
- Full `AccessibilityNodeProvider` for TalkBack (Tier C from the upgrade list).
