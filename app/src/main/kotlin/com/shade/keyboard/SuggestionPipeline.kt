package com.shade.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import android.util.TypedValue
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import com.shade.keyboard.gif.GifController
import com.shade.keyboard.translate.TranslationService
import com.shade.keyboard.gif.GifItem
import com.shade.keyboard.gif.GifConfig
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.preference.PreferenceManager
import com.shade.keyboard.ai.NeuralPredictionEngine
import com.shade.keyboard.analytics.TouchBiasModel
import com.shade.keyboard.analytics.TypingStatsStore
import com.shade.keyboard.emoji.EmojiPredictionEngine
import com.shade.keyboard.engine.GrammarEngine
import com.shade.keyboard.engine.HeuristicSuggestionEngine
import com.shade.keyboard.engine.CandidateRanker
import com.shade.keyboard.engine.NeuralSpellChecker
import com.shade.keyboard.engine.TfliteCandidateRankingScorer
import com.shade.keyboard.engine.TfliteContextEncoder
import com.shade.keyboard.engine.TfliteNextWordScorer
import com.shade.keyboard.engine.EmojiPredictor
import com.shade.keyboard.engine.LanguageModelEngine
import com.shade.keyboard.engine.UserWordMemory
import com.shade.keyboard.engine.AdaptiveWordMemory
import com.shade.keyboard.engine.AppContextModel
import com.shade.keyboard.engine.AutocorrectSuppressor
import com.shade.keyboard.engine.ConfidenceCalibrator
import com.shade.keyboard.engine.CorrectionConfidence
import com.shade.keyboard.engine.OnlineRankerTrainer
import com.shade.keyboard.engine.RankingFeatureExtractor
import com.shade.keyboard.engine.RewriteModelManager
import com.shade.keyboard.engine.SessionBigramBoost
import com.shade.keyboard.engine.SentencePositionScorer
import com.shade.keyboard.engine.SuggestionRejectionTracker
import com.shade.keyboard.engine.AcceptanceRateAdapter
import com.shade.keyboard.engine.PerAppVocabularySnapshot
import com.shade.keyboard.motion.ShadeMotion
import com.shade.keyboard.slide.SlideDecoder
import com.shade.keyboard.voice.VoiceInputController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.ArrayDeque

/**
 * Owns the suggestion flow: candidate generation, learned re-ranking, offensive filtering, casing, strip presentation and candidate selection. Numerically locked by verify_emoji_search_v9 / verify_v8_logic; the suggestion-quality patch deliberately rebalances the ranker merge and injection weighting (see the §-marked comments) while leaving feature extraction untouched. Extracted from ShadeService (v11 refactor).
 *
 * Every method body runs inside `with(svc)` so member references
 * resolve exactly as they did when these were members of
 * ShadeService - locals shadow identically and `with` being inline
 * preserves bare-return control flow. Behavior is unchanged.
 */
internal class SuggestionPipeline(private val svc: ShadeService) {

    // R2 — a learnable word is letters/marks (plus internal ' / -), bounded
    // length, no whitespace; excludes emoji, clipboard sentences and URLs.
    private fun isWordChar(c: Char): Boolean {
        if (c.isLetterOrDigit() || c == '\'' || c == '-') return true
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.SPACING_COMBINING_MARK.toInt()
    }

    private fun isLearnableWord(s: String): Boolean =
        s.length in 1..30 && s.any { it.isLetter() } &&
        !s.any { it.isWhitespace() } && s.all { isWordChar(it) }

    internal fun learnedRankCandidates(
        candidates: List<String>,
        typedPrefix: String,
        contextWords: List<String>
    ): List<String> { with(svc) {
        val scorer = rankingScorer ?: return candidates
        if (candidates.size <= 1) return candidates

        // Neural ranker features (contract of ranking/ranker_en.tflite, see
        // RankingFeatureExtractor): candidate chars vs typed chars, persistent
        // user unigram count and session bigram count, type code 0 (words).
        val prev1 = contextWords.lastOrNull()
        // §1 — rebalanced merge. With 0.32/0.12 the stage-1 leader was
        // mathematically unbeatable: its base term was (1-w)·1.0 = 0.68 (en)
        // while the best possible challenger at index 1 scored
        // (1-w)·0.5 + w·1.0 = 0.66 — so the TFLite ranker was a tiebreaker,
        // not a ranker, and only the small learned/session boosts could ever
        // overturn merge order. 0.70/0.50 keeps merge order as a prior but
        // lets genuine model + feature evidence win.
        val modelWeight = if (dictIds().split(',').firstOrNull()?.trim()?.equals("en", ignoreCase = true) == true) 0.70f else 0.50f

        val ranked = candidates.take(8).mapIndexed { index, candidate ->
            val base = 1f / (index + 1)
            val features = RankingFeatureExtractor.extract(
                candidate = candidate,
                typedWord = typedPrefix,
                typeCode = RankingFeatureExtractor.TYPE_WORD,
                userUnigramCount = userWordMemory.count(candidate),
                userBigramCount = sessionBigramBoost.bigramCount(prev1, candidate)
            )
            candidate to (base to features)
        }

        val batchScores = runCatching {
            scorer.scoreBatch(ranked.map { it.second.second })
        }.getOrElse {
            FloatArray(ranked.size) { idx -> runCatching { scorer.score(ranked[idx].second.second) }.getOrDefault(0f) }
        }

        val newRankedFeatures = HashMap<String, FloatArray>(ranked.size)
        val newRawScores = HashMap<String, Float>(ranked.size)
        val rescored = ranked.mapIndexed { index, item ->
            val candidate = item.first
            val features = item.second.second
            val base = item.second.first
            val learnedBoost = adaptiveWordMemory.score(candidate) * 0.10f + appContextModel.score(candidate) * 0.08f
            // 4l — session bigram boost: tiny boost for candidates the user has
            // already used in this IME session, and for candidates that follow
            // the last context word in the session bigram.
            val sessionBoost = sessionBigramBoost.score(candidate) * 0.06f +
                sessionBigramBoost.bigramScore(contextWords.lastOrNull(), candidate) * 0.04f
            val adapted = onlineRankerTrainer.score(features)
            val raw = batchScores.getOrElse(index) { 0f }
            val calibrated = confidenceCalibrator.calibrated(raw)
            val modelScore = calibrated * 0.7f + adapted * 0.3f
            newRankedFeatures[candidate] = features
            newRawScores[candidate] = raw
            val combined = (1f - modelWeight) * base + modelWeight * modelScore + learnedBoost + sessionBoost
            candidate to combined
        }
        // Snapshot-then-putAll so readers see either old or new state,
        // never half-mutated maps.
        lastRankedFeatures.apply { clear(); putAll(newRankedFeatures) }
        lastRawRankerScores.apply { clear(); putAll(newRawScores) }
        // §3 — [lastShownCandidates] is now the FINAL displayed list, set in
        // [setCandidates] after injections and finalization, so rejection
        // tracking and negative ranker updates attribute to words the user
        // actually saw (the pre-injection list penalized words that were
        // already displaced off the strip).
        return rescored.sortedByDescending { it.second }.map { it.first } + candidates.drop(8)
    }}

    /**
     * §3 — extract ranker features (and raw model scores) for the FINAL shown
     * words that [learnedRankCandidates] never featured — injected candidates
     * and spell fixes land here. Without this, tapping an injected winner
     * silently skipped both the positive ranker update (no features) and the
     * calibrator acceptance sample (no raw score) — learning ignored exactly
     * the candidates that bypassed the ranker.
     */
    private fun ensureFinalShownFeatures(
        shown: List<String>,
        prefix: String,
        touches: List<FloatArray>,
        layout: Map<Char, Key>?,
        contextWords: List<String>,
        prev2: String?,
        prev1: String?,
        neuralPredictions: List<Pair<String, Float>>
    ) { with(svc) {
        val missing = shown.take(3).filter { it.isNotEmpty() && !lastRankedFeatures.containsKey(it) }
        if (missing.isEmpty()) return@with
        missing.forEach { word ->
            val features = RankingFeatureExtractor.extract(
                candidate = word,
                typedWord = prefix,
                typeCode = RankingFeatureExtractor.TYPE_WORD,
                userUnigramCount = userWordMemory.count(word),
                userBigramCount = sessionBigramBoost.bigramCount(prev1, word)
            )
            lastRankedFeatures[word] = features
            lastRawRankerScores[word] = runCatching { rankingScorer?.score(features) ?: 0f }.getOrDefault(0f)
        }
    }}

    internal fun updateSuggestions() { with(svc) {
        if (!svc.slotsReady()) return
        suggestJob?.cancel()
        suggestDebounceJob?.cancel()
        if (suggestionsOn == false || suggestionsAllowed == false) {
            setCandidates(emptyList())
            return
        }
        // 4e — field-type gate: passwords, numbers, phone, datetime, and
        // explicitly-marked "no suggestions" fields suppress the bar entirely.
        if (!FieldTypeDetector.shouldShowSuggestions(currentFieldType)) {
            setCandidates(emptyList())
            return
        }

        val typedWord: String
        val composingActive = composing.isNotEmpty()
        if (composingActive) {
            typedWord = composing.toString()
        } else if (canCompose == false && suggestionsAllowed) {
            typedWord = currentTypedWordFromField()
        } else {
            typedWord = ""
        }

        // 4e — URL / email / phone detection. If the user is mid-typing an
        // email or URL, suppress suggestions to avoid interrupting the flow.
        val beforeCursor = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString() ?: ""
        val urlDetection = UrlEmailDetector.detect(typedWord, beforeCursor)
        if (UrlEmailDetector.suppressSuggestions(urlDetection)) {
            setCandidates(emptyList())
            return
        }

        if (typedWord.isEmpty()) {
            val contextWords = contextWordsExcluding("")
            val desiredLocale = desiredNeuralLocale(contextWords)
            if (neuralLocale != desiredLocale) {
                scope.launch(Dispatchers.Default) { reloadNeuralEngine(desiredLocale) }
            }
            val prev1 = contextWords.lastOrNull()
            val prev2 = contextWords.dropLast(1).lastOrNull()
            val context = neuralContext("")

            suggestDebounceJob = scope.launch {
                delay(ShadeService.SUGGEST_DEBOUNCE_MS)
                if (!isActive) return@launch
                suggestJob = scope.launch(Dispatchers.Default) {
                    val neuralNext = withContext(Dispatchers.Default) {
                        // Neural improvement #3 — use beam search for phrase
                        // suggestions when the user has multi-word suggestions
                        // enabled. Beam search produces 2-word continuations
                        // ("are you", "to see") that greedy single-token
                        // prediction misses. Falls back to greedy for
                        // word-by-word mode (faster, single-word results).
                        if (phraseSuggestionsOn) {
                            (neural as? NeuralPredictionEngine)
                                ?.predictNextBeam(context, null, 8)
                                ?.map { it.first }
                                .orEmpty()
                        } else {
                            neural
                                ?.predictNext(context, null, 6)
                                ?.map { it.first }
                                .orEmpty()
                        }
                    }
                    val dictionaryNext = prev1
                        ?.let { dict.predictNext(prev2, it, 8) }
                        .orEmpty()
                    val smartNext = HeuristicSuggestionEngine.smart(contextWords, null, 12)
                    val trigramNext = HeuristicSuggestionEngine.trigram(contextWords, null, 8)
                    val phraseNext = HeuristicSuggestionEngine.phrase(prev1, 6)
                    val grammarNext = GrammarEngine.predictNext(contextWords, 6)
                    // §1/N1 — interleave by score, not by fixed concatenation
                    // order. The heuristic sources belong in the 0.10-weight
                    // `heuristics` param: concatenated into `neural` (0.65) each
                    // source's #1 tied the RNN's #1 and RNN #2 lost to all four
                    // heuristic #1s, wasting the LM's depth.
                    val merged = CandidateRanker.mergeNextWordScored(
                        neural = CandidateRanker.scoredByPosition(neuralNext),
                        heuristics = CandidateRanker.scoredByPosition(smartNext) +
                            CandidateRanker.scoredByPosition(trigramNext) +
                            CandidateRanker.scoredByPosition(grammarNext) +
                            CandidateRanker.scoredByPosition(phraseNext),
                        dictionary = CandidateRanker.scoredByPosition(dictionaryNext),
                        limit = 3
                    )

                    val learned = safeSuggestion(merged) {
                        withContext(Dispatchers.Default) {
                            learnedRankCandidates(
                                candidates = merged,
                                typedPrefix = "",
                                contextWords = contextWords
                            )
                        }
                    }
                    val finalMerged = learned.ifEmpty { merged }
                    val list: List<String> = when {
                        finalMerged.isNotEmpty() -> centerBest(filterOffensive(finalMerged))
                        atSentenceEndBeforeCursor() && isEnglishLocale() -> listOf("The", "I", "I'm")
                        else -> emptyList()
                    }
                    withContext(Dispatchers.Main.immediate) { setCandidates(list) }
                }
            }
            return
        }

        val prefix = typedWord
        val contextWords = contextWordsExcluding(typedWord)
        val desiredLocale = desiredNeuralLocale(contextWords)
        if (neuralLocale != desiredLocale) {
            scope.launch(Dispatchers.Default) { reloadNeuralEngine(desiredLocale) }
        }
        val prev1 = contextWords.lastOrNull()
        val prev2 = contextWords.dropLast(1).lastOrNull()
        val touches = if (composingActive) touchTrace.last(touchTrace.size()) else emptyList()
        val layout = kbKeyLayout()
        val neuralCtx = neuralContext(typedWord)

        suggestDebounceJob = scope.launch {
            delay(ShadeService.SUGGEST_DEBOUNCE_MS)
            if (!isActive) return@launch
            suggestJob = scope.launch(Dispatchers.Default) {
                // #11 — short-circuit on SuggestionCache hit when (prev2, prev1, prefix)
                // match a recently-computed result. Skips the whole pipeline.
                // §5 — the pipeline output depends on more than locale and
                // incognito: the current app (per-app vocabulary), the bar
                // toggles, and structural learning state all change the list.
                // [learningGeneration] only moves on structural events, so
                // per-word learning drift stays bounded by the cache TTL.
                com.shade.keyboard.engine.SuggestionCache.salt = "$configuredLocale|$incognito|" +
                    "${currentAppPackage().orEmpty()}|$phraseSuggestionsOn|$blockOffensiveOn|$learningGeneration"
                com.shade.keyboard.engine.SuggestionCache.get(prev2, prev1, prefix)?.let { cached ->
                    // R3 — refresh feature vectors for the cached list: the hit
                    // path skips ranking, so lastRankedFeatures still held the
                    // PREVIOUS prefix's vectors and acceptance learning trained
                    // on stale data. fill-if-missing never clobbers fresh ones.
                    ensureFinalShownFeatures(
                        cached, prefix, touches, layout, contextWords, prev2, prev1, emptyList()
                    )
                    withContext(Dispatchers.Main.immediate) { setCandidates(cached) }
                    return@launch
                }
                val known = dict.contains(prefix)
                val stage1 = LinkedHashSet<String>()
                // Strict prefix rule: every suggestion must match every typed alphabet
                // after the last space.
                val effectivePrefix = prefix
                // 4k — keep the raw predictions (with scores) so we can inject
                // user vocabulary before flattening to strings.
                val rawPredictions = withContext(Dispatchers.Default) {
                    neural?.predictNext(neuralCtx, effectivePrefix, 24).orEmpty()
                }
                val neuralPrefixCandidates = if (effectivePrefix.isNotEmpty() && personalLearningAllowed()) {
                    val userWords = userWordMemory.snapshot()
                        .filterKeys { it.startsWith(effectivePrefix, ignoreCase = true) }
                    com.shade.keyboard.engine.UserVocabularyInjector.inject(
                        rawPredictions, userWords, effectivePrefix, 24
                    ).map { it.first }
                } else {
                    rawPredictions.map { it.first }
                }
                stage1.addAll(neuralPrefixCandidates)
                val grammarCandidates = GrammarEngine.suggest(contextWords, prefix, 6)
                val grammarTop = grammarCandidates.firstOrNull()
                val smartCandidates = HeuristicSuggestionEngine.smart(contextWords, effectivePrefix, 16)
                val trigramCandidates = HeuristicSuggestionEngine.trigram(contextWords, effectivePrefix, 12)
                val phraseCandidates = HeuristicSuggestionEngine.phrase(prev1, effectivePrefix, 12)
                val contextCandidates = prev1
                    ?.let { dict.predictNext(prev2, it, 48) }
                    .orEmpty()
                    .filter { it.startsWith(effectivePrefix, ignoreCase = true) }
                stage1.addAll(grammarCandidates)
                stage1.addAll(smartCandidates)
                stage1.addAll(trigramCandidates)
                stage1.addAll(phraseCandidates)
                stage1.addAll(contextCandidates)
                stage1.addAll(dict.suggestAsync(prefix, 48))

                // Enforce strict prefix matching even if a source injected a correction
                // or unrelated candidate.
                // R6 — case-insensitive dedup so "the"/"The" don't both burn slots.
                val seenLower = HashSet<String>()
                val strictStage1 = stage1.filter {
                    it.startsWith(prefix, ignoreCase = true) && seenLower.add(it.lowercase())
                }
                stage1.clear()
                stage1.addAll(strictStage1)

                val stage2 = if (prev1 != null) {
                    // R7 — pass prev2: the bigramModel rerank path is
                    // trigram-aware and prev2 is in scope here; the
                    // bundledBigrams path ignores it anyway.
                    dict.rerankWithContext(prev2, prev1, stage1.toList())
                } else stage1.toList()

                val stage3 = if (stage2.size >= 2 && layout != null && touches.isNotEmpty()) {
                    val a = stage2[0]
                    val b = stage2[1]
                    if (a.length == b.length && a.take(2) == b.take(2)) {
                        SpatialScorer.tiebreak(stage2, touches, layout)
                    } else stage2
                } else stage2

                var ranked = withContext(Dispatchers.Default) {
                    // Neural improvement #5 — pass bigram scores into the neural
                    // reranker so it can do log-linear interpolation instead of
                    // independent ranking. The bigram model catches user-specific
                    // phrases and rare words the neural model misses.
                    val bigramScores = dict.bigramModel
                        ?.predict(prev2, prev1, stage3.size * 2)
                        ?.associate { it.first.lowercase() to it.second }
                        .orEmpty()
                    (neural as? NeuralPredictionEngine)
                        ?.rerankPrefix(neuralCtx, effectivePrefix, stage3, bigramScores)
                        ?: neural?.rerankPrefix(neuralCtx, effectivePrefix, stage3)
                } ?: stage3
                ranked = safeSuggestion(ranked) {
                    withContext(Dispatchers.Default) {
                        learnedRankCandidates(
                            candidates = ranked,
                            typedPrefix = prefix,
                            contextWords = contextWords
                        )
                    }
                }

                // 4j — typed-word pipeline injections.
                // §2 — injection sources no longer hard-prepend in front of the
                // ML ranking (that let ~15 bypass rounds unconditionally override
                // the ranker — a time-aware word jumped to position 0 regardless
                // of context fit). They now feed ONE final scored pass,
                // [SuggestionFinalizer.mergeScored]: the ML list keeps a
                // full-weight positional prior and each source competes by
                // weight × within-source rank. Hard prepends are reserved for
                // deterministic fixes (confusable pairs, §4 spell fix).
                val injections = ArrayList<Pair<Float, List<String>>>(8)
                // #1 — Multi-word phrase completion (good→morning, thank→you, ...).
                // Gated behind phraseSuggestionsOn: the bigram table contains
                // single-word completions (good→morning) AND multi-word ones
                // (good→"to see"); in word-by-word mode we still want the
                // single-word continuations, so we keep the source but filter
                // out space-containing entries.
                if (phraseSuggestionsOn) {
                    val phraseCompletions = com.shade.keyboard.engine.PhraseCompletion
                        .suggestPhraseCompletions(contextWords, prefix, limit = 3)
                    if (phraseCompletions.isNotEmpty()) injections.add(0.90f to phraseCompletions)
                } else {
                    val phraseCompletions = com.shade.keyboard.engine.PhraseCompletion
                        .suggestPhraseCompletions(contextWords, prefix, limit = 3)
                        .filter { !it.contains(' ') }
                    if (phraseCompletions.isNotEmpty()) injections.add(0.90f to phraseCompletions)
                }
                // #15 — Smart compose for common responses (ok → ok sounds good, ...).
                // These templates are inherently multi-word, so the whole source
                // is skipped in word-by-word mode.
                if (phraseSuggestionsOn && FieldTypeDetector.shouldSmartCompose(currentFieldType)) {
                    val smartResponses = com.shade.keyboard.engine.SmartResponseComposer.suggest(prefix, limit = 2)
                    if (smartResponses.isNotEmpty()) injections.add(0.85f to smartResponses)
                }
                // #6+#7 — Cross-sentence context + sentence starters.
                val extendedContext = com.shade.keyboard.engine.CrossSentenceContext.extendContext(contextWords)
                val rawBeforeForSentence = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty()
                    .let { if (prefix.isNotEmpty() && it.endsWith(prefix, ignoreCase = true)) it.dropLast(prefix.length) else it }
                if (com.shade.keyboard.engine.CrossSentenceContext.isSentenceStart(extendedContext) ||
                    com.shade.keyboard.engine.CrossSentenceContext.endsWithSentenceTerminator(rawBeforeForSentence)) {
                    val starters = com.shade.keyboard.engine.CrossSentenceContext.sentenceStarters(prefix, limit = 2)
                    // In word-by-word mode drop multi-word starters ("let me",
                    // "thank you", "it would") and keep single-word ones ("the",
                    // "I", "how", ...).
                    val filteredStarters = if (phraseSuggestionsOn) starters
                        else starters.filter { !it.contains(' ') }
                    if (filteredStarters.isNotEmpty()) injections.add(0.70f to filteredStarters)
                }
                // #18 — Greeting/time-aware bias (prefix-filtered; its weight
                // keeps it from unconditionally owning the bar).
                val timeWords = com.shade.keyboard.engine.GreetingTimeBias.timeAwareWords(prefix)
                if (timeWords.isNotEmpty()) injections.add(0.60f to timeWords)
                // #9 — Smart number prediction (after "in", "costs", "at", ...).
                val numberSuggestions = SuggestionHeuristics.numbersForContext(extendedContext, prefix)
                if (numberSuggestions.isNotEmpty()) injections.add(0.75f to numberSuggestions)
                // #17 — Emoji context prediction (after "love", "happy", ...).
                val emojiSuggestions = com.shade.keyboard.engine.EmojiContextPredictor.suggest(prev1, limit = 2)
                if (emojiSuggestions.isNotEmpty()) injections.add(0.50f to emojiSuggestions)
                // #25 — Neural next-word prediction: the RNN LM fills gaps when the
                // dictionary has no strong continuation for the previous words.
                if (prefix.isEmpty()) {
                    val nwpWords = nextWordScorer
                        ?.takeIf { it.isAvailable }
                        ?.suggest(contextWords, limit = 2)
                        .orEmpty()
                    if (nwpWords.isNotEmpty()) injections.add(0.70f to nwpWords)
                }
                // #14 — Rejection-pattern learning: drop recently-rejected candidates.
                ranked = rejectionTracker.filter(ranked)
                // R4 — remember a deterministic fix prepended to the ranked list
                // so the bandit swap below can never demote it.
                var prependedFix: String? = null
                // #20 — Per-app vocabulary snapshot.
                val appWords = perAppVocabSnapshot.wordsForPrefix(prefix, limit = 2)
                if (appWords.isNotEmpty()) injections.add(0.80f to appWords)
                // #22 — Confusable-pairs correction (their/there/they're, etc.).
                // Deterministic context fix — keeps its hard prepend.
                com.shade.keyboard.engine.ConfusablePairs.correct(prefix, prev1)?.let { conf ->
                    if (ranked.none { it.equals(conf, ignoreCase = true) }) {
                        ranked = (listOf(conf) + ranked).distinct()
                        prependedFix = conf
                    }
                }
                // #23 — Phonetic-match suggestions (for typo class that isn't a
                // keyboard slip but a pronunciation-based misspelling).
                if (ranked.size < 3) {
                    val phonetic = com.shade.keyboard.engine.PhoneticMatcher
                        .findPhoneticMatches(prefix, dict.suggest(prefix, 32), limit = 2)
                    if (phonetic.isNotEmpty()) {
                        ranked = (ranked + phonetic).distinct()
                    }
                }
                // #24 — Punctuation predictor: append a hint punctuation candidate
                // when the user has typed a recognized sentence ender.
                com.shade.keyboard.engine.PunctuationPredictor
                    .suggestPunctuation(contextWords)?.let { punct ->
                        if (ranked.none { it == punct }) {
                            ranked = (ranked + listOf(punct)).distinct().take(6)
                        }
                    }
                // #21 — Multi-word sentence completion ("How are you" → "doing today?").
                // Skipped entirely in word-by-word mode; in phrase mode keep only
                // the single-word completions ("soon", "later", "tomorrow", ...).
                run {
                    val sentenceCompletions = com.shade.keyboard.engine.SentenceCompleter
                        .suggest(contextWords, limit = 2)
                    val kept = if (phraseSuggestionsOn) sentenceCompletions
                        else sentenceCompletions.filter { !it.contains(' ') }
                    if (kept.isNotEmpty()) injections.add(0.85f to kept)
                }
                // §4 — controlled center-slot exception: when the typed word is
                // not a dictionary word, its highest-confidence correction may
                // sit in the bar even though it doesn't extend the prefix.
                // Users expect to see "the" while typing "teh", not only after
                // pressing space; the strict-prefix filter above made that
                // impossible. Gated by the same confidence machinery as
                // autocorrect (see [ShadeService.barSpellFix]).
                if (!known && prefix.length >= 3) {
                    barSpellFix(prefix, prev1)?.let { fix ->
                        if (ranked.none { it.equals(fix, ignoreCase = true) }) {
                            ranked = (listOf(fix) + ranked).distinct()
                            prependedFix = fix
                        }
                    }
                }
                // §2/R4 — one final scored ranking pass, keeping the scores for
                // the bandit swap gate below; then the rejection filter once more
                // over the merged list: injected candidates were never
                // rejection-filtered when they hard-prepended.
                val mergedScores = com.shade.keyboard.engine.SuggestionFinalizer
                    .mergeScoredWithScores(ranked, injections)
                ranked = mergedScores.keys.toList()
                ranked = rejectionTracker.filter(ranked)
                // #5/R5 — morphological diversity now runs AFTER the final merge:
                // injections + mergeScored reintroduce stem duplicates that the
                // old early dedup pass had already removed.
                ranked = com.shade.keyboard.engine.MorphologicalDiversity.deduplicate(ranked, limit = 6)
                // #11 — Suggestion cache: nothing to inject here, but record the
                // final ranked list so subsequent keystrokes can hit the cache.
                // #32/R4 — Multi-armed bandit exploration: swap top-2 to learn
                // whether the runner-up would have been accepted — but only on a
                // near-tie, and never a deterministic fix or a known top over an
                // unknown runner-up (the raw swap buried corrections and biased
                // acceptance stats).
                if (onlineRankerTrainer.shouldExplore() && ranked.size > 2) {
                    // R4 — swap only on a REAL near-tie: a missing score must
                    // not read as 0-0. Both filters above return the original
                    // strings today, so both lookups hit the merged map, but a
                    // future rewrite that substitutes tokens would otherwise
                    // turn two unknown scores into a false "tie".
                    val s0 = mergedScores[ranked[0]]
                    val s1 = mergedScores[ranked[1]]
                    val knownTopVsUnknownRunner = dict.contains(ranked[0].lowercase()) &&
                        !dict.contains(ranked[1].lowercase())
                    if (s0 != null && s1 != null && ranked[0] != prependedFix &&
                        !knownTopVsUnknownRunner &&
                        kotlin.math.abs(s0 - s1) < 0.15f
                    ) {
                        val mutable = ranked.toMutableList()
                        val tmp = mutable[0]
                        mutable[0] = mutable[1]
                        mutable[1] = tmp
                        ranked = mutable
                    }
                }

                val shown = com.shade.keyboard.engine.SuggestionFinalizer.finalize(
                    prefix = prefix,
                    known = known,
                    grammarTop = if (blockOffensiveOn) grammarTop?.takeUnless {
                        com.shade.keyboard.engine.ProfanityFilter.isOffensive(it)
                    } else grammarTop,
                    ranked = filterOffensive(ranked),
                    phraseSuggestionsOn = phraseSuggestionsOn
                )
                // §3 — feature the FINAL shown top-3 regardless of source so
                // acceptances on injected candidates train the ranker too.
                ensureFinalShownFeatures(shown, prefix, touches, layout, contextWords, prev2, prev1, rawPredictions)
                // Cache the final list (after typo/grammar handling) so a cache hit
                // shows exactly what the full pipeline would.
                com.shade.keyboard.engine.SuggestionCache.put(prev2, prev1, prefix, shown)
                withContext(Dispatchers.Main.immediate) { setCandidates(shown) }
            }
        }
    }}

    internal fun centerBest(l: List<String>): List<String> = with(svc) {
        com.shade.keyboard.engine.SuggestionFinalizer.centerBest(l)
    }

    /**
     * Suggestion-level profanity gate ("block offensive words"). Removes
     * offensive candidates from what the bar shows or what autocorrect may
     * pick; the user's own typed word always passes untouched.
     */
    internal fun filterOffensive(candidates: List<String>): List<String> = with(svc) {
        if (blockOffensiveOn) candidates.filterNot {
            com.shade.keyboard.engine.ProfanityFilter.isOffensive(it)
        } else candidates
    }

    /** Case the suggestion bar should use right now (see [SuggestionCasing.styleFor]). */
    internal fun currentSuggestionStyle(): SuggestionCasing.Style { with(svc) {
        val typed = when {
            composing.isNotEmpty() -> composing.toString()
            !canCompose -> currentTypedWordFromField()
            else -> ""
        }
        return SuggestionCasing.styleFor(mode == ShadeService.Mode.LETTERS, shiftState, typed)
    }}

    internal fun casedCandidates(raw: List<String>): List<String> { with(svc) {
        if (raw.isEmpty()) return raw
        val style = currentSuggestionStyle() // computed once per update, not per suggestion
        return raw.map { SuggestionCasing.apply(it, style, lastClipText) }
    }}

    /** Re-cases the visible suggestions after Shift / Caps Lock changed, without recomputing them. */
    internal fun refreshCandidateCase() { with(svc) {
        if (!svc.slotsReady() || candidates.isEmpty()) return
        val display = casedCandidates(candidates)
        if (display == shownWords) return
        shownWords = display
        for (i in 0 until 3) {
            val w = display.getOrNull(i) ?: ""
            if (slots[i].text.toString() != w) slots[i].text = w
        }
    }}

    /** Empties both the candidate lists and the three slots (stale words must not survive a field change). */
    internal fun resetCandidates() { with(svc) {
        candidates = emptyList()
        shownWords = emptyList()
        // §3 — the strip is empty; nothing on it is attributable anymore.
        lastShownCandidates = emptyList()
        if (!svc.slotsReady()) return
        slotAnimator?.cancel()
        for (i in 0 until 3) {
            slots[i].text = ""
            slotOlds[i].text = ""
            slotOlds[i].alpha = 0f
            slotOlds[i].visibility = View.GONE
        }
    }}

    internal fun setCandidates(list: List<String>) { with(svc) {
        // Clear the clipboard chip whenever real suggestions arrive, so it
        // doesn't reappear after the user starts typing. When the list is empty
        // and a chip is available, show the chip as the only candidate.
        if (list.isNotEmpty()) {
            lastClipText = null
        }
        val effective: List<String> =
            if (list.isEmpty() && lastClipText != null && !incognito &&
                !fieldNoPersonalizedLearning && !fieldSecureInput
            ) {
                listOf(lastClipText!!)
            } else {
                list
            }
        // Re-case for the current keyboard state (Shift / Caps Lock / typed capital).
        val display = casedCandidates(effective)
        val rawChanged = effective != candidates
        if (!rawChanged && display == shownWords) return
        if (rawChanged) {
            if (insightsAllowed() && effective.isNotEmpty()) typingStatsStore.recordSuggestionShown()
            // 4h — track global shown-count for the acceptance-rate adapter.
            if (personalLearningAllowed() && effective.isNotEmpty()) {
                acceptanceRateAdapter.recordShown()
            }
            if (calibratorLearningAllowed()) {
                effective.take(3).filter { it.isNotEmpty() }.forEach { w ->
                    lastRawRankerScores[w]?.let { raw -> confidenceCalibrator.recordShown(raw) }
                }
            }
        }
        val old = shownWords
        candidates = effective
        shownWords = display
        // §3 — learning attribution keys on the FINAL displayed list (see the
        // endWord rejection gate and pickSuggestion negatives). The clipboard
        // chip is not a suggestion, so it does not join the list.
        // N2 — an empty bar must CLEAR the attribution list: keeping the old
        // list + old timestamp let a later commit "reject" a word that was no
        // longer on screen (URL/email suppression, chip-only states).
        lastShownCandidates = if (list.isNotEmpty()) effective else emptyList()
        if (rawChanged) candidatesShownAt = SystemClock.elapsedRealtime()
        slotAnimator?.cancel()

        var anyChanged = false
        for (i in 0 until 3) {
            val newWord = display.getOrNull(i) ?: ""
            val oldWord = old.getOrNull(i) ?: ""
            if (newWord == oldWord) continue
            anyChanged = true

            slotOlds[i].text = oldWord
            slotOlds[i].alpha = 1f
            slotOlds[i].visibility = if (oldWord.isEmpty()) View.GONE else View.VISIBLE

            slots[i].text = newWord
            slots[i].alpha = 0f
            slots[i].scaleX = if (i == 1) 0.95f else 1f
            slots[i].scaleY = if (i == 1) 0.95f else 1f
            slots[i].setTypeface(null, if (i == 1) Typeface.BOLD else Typeface.NORMAL)
        }
        if (!anyChanged) return

        slotAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShadeMotion.CANDIDATE_SWAP_MS
            interpolator = ShadeMotion.FAST_OUT_SLOW_IN
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in 0 until 3) {
                    slotOlds[i].alpha = 1f - t
                    slots[i].alpha = t
                    if (i == 1) {
                        val s = 0.95f + 0.05f * t
                        slots[i].scaleX = s
                        slots[i].scaleY = s
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    for (i in 0 until 3) {
                        slotOlds[i].alpha = 0f
                        slotOlds[i].visibility = View.GONE
                        slots[i].alpha = 1f
                        slots[i].scaleX = 1f
                        slots[i].scaleY = 1f
                    }
                }
            })
            start()
        }
    }}

    internal fun pickSuggestion(i: Int) { with(svc) {
        val w = candidates.getOrNull(i) ?: return
        if (w.isEmpty()) return
        // `w` (raw) is used for all learning/statistics; `shown` is what the user tapped and what is committed.
        val shown = shownWords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: w
        // R2 — capture the clipboard chip BEFORE clearing it: an accepted chip
        // is a paste, not a suggestion acceptance, so it must not train anything.
        val wasClipboardChip = lastClipText != null && w == lastClipText
        if (wasClipboardChip) lastClipText = null
        if (insightsAllowed()) typingStatsStore.recordSuggestionAccepted()
        // R1 — strip the typed fragment ONCE. The field still ends with the
        // composing prefix here, so excluding `w` never matched and every
        // learning call saw prev1 = "wor" instead of the real previous word.
        // Also one getTextBeforeCursor(400) IPC instead of three.
        val typed = composing.toString().ifEmpty { currentTypedWordFromField() }
        val prevWords = contextWordsExcluding(typed)
        val prev1 = prevWords.lastOrNull()
        val prev2 = prevWords.dropLast(1).lastOrNull()
        // R2 — learn only real words: emoji, URLs and clipboard sentences
        // corrupt every memory below.
        val learnable = isLearnableWord(w) && !wasClipboardChip
        // 4i — record acceptance for the acceptance-rate adapter + propagate
        // learning-rate multiplier to the online ranker; observe session
        // bigram; record sentence-position acceptance.
        if (personalLearningAllowed() && learnable) {
            acceptanceRateAdapter.recordAccepted()
            onlineRankerTrainer.setLearningRateMultiplier(acceptanceRateAdapter.learningRateMultiplier())
        }
        if (learnable) sessionBigramBoost.observeBigram(prev1, w)
        val pos = sentencePositionScorer.currentPosition(prevWords)
        if (personalLearningAllowed() && learnable) {
            sentencePositionScorer.recordAcceptance(pos)
        }

        if (learnable && learnOn && personalLearningAllowed()) {
            if (userWordMemory.score(w) <= 0f) learningGeneration++ // §5 — structural event
            userWordMemory.bump(w)
        }
        if (learnable && adaptiveLearningAllowed()) {
            adaptiveWordMemory.record(w)
        }
        if (learnable && appContextLearningAllowed()) {
            appContextModel.recordWord(w)
        }
        if (learnable && (rankerLearningAllowed() || calibratorLearningAllowed())) {
            lastRankedFeatures[w]?.let { positive ->
                if (rankerLearningAllowed()) {
                    val negatives = lastShownCandidates
                        .filterNot { it.equals(w, ignoreCase = true) }
                        .mapNotNull { lastRankedFeatures[it] }
                    onlineRankerTrainer.adapt(positive, negatives)
                }
                if (calibratorLearningAllowed()) {
                    lastRawRankerScores[w]?.let { raw -> confidenceCalibrator.recordAcceptance(raw) }
                }
            }
        }

        val ic = currentInputConnection ?: return
        clearUndo()
        // A new commit supersedes any pending autocorrect revert: otherwise
        // backspace after tapping a suggestion whose text equals the older
        // corrected word would "revert" that older correction instead of
        // undoing the tap.
        clearCorrectionEvent()
        // §6 — one backspace after accepting a suggestion restores the typed
        // prefix (or deletes the tapped word wholesale for next-word picks)
        // instead of nibbling the committed text char-by-char, matching the
        // autocorrect revert that backspace() already performs.
        val spaceAppended = shown.isNotEmpty() && shown[0].isLetter()
        val undoChars = shown.length + if (spaceAppended) 1 else 0
        if (undoChars > 0) {
            val cursorNow = currentCursorPos()
            val expectedCursor = if (cursorNow >= 0) cursorNow - typed.length + undoChars else -1
            pushUndo(undoChars, typed, expectedCursor)
        }
        ic.beginBatchEdit()
        try {
            if (composing.isNotEmpty()) {
                composing.clear()
                ic.commitText(shown, 1)
            } else {
                if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
                ic.commitText(shown, 1)
            }
            if (typed.isNotEmpty() && tailValid && tail.endsWith(typed, ignoreCase = true)) {
                popTail(typed.length)
            }
            appendTail(shown)
            if (shown.isNotEmpty() && shown[0].isLetter()) {
                ic.commitText(" ", 1)
                appendTail(" ")
            }
        } finally {
            ic.endBatchEdit()
        }
        // R2 — the trailing dict.learn is part of the same "a paste must not
        // train anything" contract as the recorders above: gate it on the same
        // learnable predicate, otherwise a word clipboard chip ("hello") still
        // trains the user dictionary and bigramModel.
        if (learnable && learnOn && personalLearningAllowed() && canCompose) {
            dict.learn(w, prev2 = prev2, prev1 = prev1)
        }
        lastSpaceTime = 0L
        afterText()
    }}

}