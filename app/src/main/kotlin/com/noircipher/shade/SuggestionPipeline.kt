package com.noircipher.shade

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
import com.noircipher.shade.gif.GifController
import com.noircipher.shade.translate.TranslationService
import com.noircipher.shade.gif.GifItem
import com.noircipher.shade.gif.KlipyConfig
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.preference.PreferenceManager
import com.noircipher.shade.ai.NeuralPredictionEngine
import com.noircipher.shade.analytics.TouchBiasModel
import com.noircipher.shade.analytics.TypingStatsStore
import com.noircipher.shade.emoji.EmojiPredictionEngine
import com.noircipher.shade.engine.GrammarEngine
import com.noircipher.shade.engine.HeuristicSuggestionEngine
import com.noircipher.shade.engine.CandidateRanker
import com.noircipher.shade.engine.NeuralSpellChecker
import com.noircipher.shade.engine.TfliteCandidateRankingScorer
import com.noircipher.shade.engine.TfliteContextEncoder
import com.noircipher.shade.engine.TfliteNextWordScorer
import com.noircipher.shade.engine.EmojiPredictor
import com.noircipher.shade.engine.LanguageModelEngine
import com.noircipher.shade.engine.UserWordMemory
import com.noircipher.shade.engine.AdaptiveWordMemory
import com.noircipher.shade.engine.AppContextModel
import com.noircipher.shade.engine.AutocorrectSuppressor
import com.noircipher.shade.engine.ConfidenceCalibrator
import com.noircipher.shade.engine.CorrectionConfidence
import com.noircipher.shade.engine.OnlineRankerTrainer
import com.noircipher.shade.engine.RankingFeatureExtractor
import com.noircipher.shade.engine.RewriteModelManager
import com.noircipher.shade.engine.SessionBigramBoost
import com.noircipher.shade.engine.SentencePositionScorer
import com.noircipher.shade.engine.SuggestionRejectionTracker
import com.noircipher.shade.engine.AcceptanceRateAdapter
import com.noircipher.shade.engine.PerAppVocabularySnapshot
import com.noircipher.shade.motion.ShadeMotion
import com.noircipher.shade.slide.SlideDecoder
import com.noircipher.shade.voice.VoiceInputController
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
 * Owns the suggestion flow: candidate generation, learned re-ranking, offensive filtering, casing, strip presentation and candidate selection. Numerically locked by verify_emoji_search_v9 / verify_v8_logic - the scoring math and feature extraction are untouched. Extracted from ShadeService (v11 refactor).
 *
 * Every method body runs inside `with(svc)` so member references
 * resolve exactly as they did when these were members of
 * ShadeService - locals shadow identically and `with` being inline
 * preserves bare-return control flow. Behavior is unchanged.
 */
internal class SuggestionPipeline(private val svc: ShadeService) {

    internal fun learnedRankCandidates(
        candidates: List<String>,
        typedPrefix: String,
        touches: List<FloatArray>,
        layout: Map<Char, Key>?,
        contextWords: List<String>
    ): List<String> { with(svc) {
        val scorer = rankingScorer ?: return candidates
        if (candidates.size <= 1) return candidates

        val embedding = contextEmbeddingForCurrentLocale(contextWords)
        val contextText = contextWords.joinToString(" ")
        val neuralScores = neural
            ?.predictNext(contextText, typedPrefix, 12)
            ?.associate { it.first.lowercase() to it.second }
            .orEmpty()
        val prev1 = contextWords.lastOrNull()
        val prev2 = contextWords.dropLast(1).lastOrNull()
        val userBigramScores = dict.bigramModel
            ?.predict(prev2, prev1, 32)
            ?.associate { it.first.lowercase() to it.second }
            .orEmpty()
        val modelWeight = if (dictIds().split(',').firstOrNull()?.trim()?.equals("en", ignoreCase = true) == true) 0.32f else 0.12f

        val ranked = candidates.take(8).mapIndexed { index, candidate ->
            val base = 1f / (index + 1)
            val neural = neuralScores[candidate.lowercase()] ?: (base * 0.25f)
            val spatial = if (layout != null) SpatialScorer.score(candidate, touches, layout) else 0f
            val features = RankingFeatureExtractor.extract(
                candidate = candidate,
                typedPrefix = typedPrefix,
                baseRankScore = base,
                neuralScore = neural,
                spatialScore = spatial,
                touches = touches,
                layout = layout ?: emptyMap(),
                contextEmbedding = embedding,
                userUnigramScore = userWordMemory.score(candidate),
                userBigramScore = userBigramScores[candidate.lowercase()] ?: 0f
            )
            val modelScore = 0f
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
        lastShownCandidates = rescored.map { it.first }
        return rescored.sortedByDescending { it.second }.map { it.first } + candidates.drop(8)
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
                    val merged = CandidateRanker.mergeNextWord(
                        neural = smartNext + trigramNext + neuralNext + grammarNext + phraseNext,
                        dictionary = dictionaryNext,
                        limit = 3
                    )

                    val learned = safeSuggestion(merged) {
                        withContext(Dispatchers.Default) {
                            learnedRankCandidates(
                                candidates = merged,
                                typedPrefix = "",
                                touches = emptyList(),
                                layout = null,
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
                com.noircipher.shade.engine.SuggestionCache.salt = "$configuredLocale|$incognito"
                com.noircipher.shade.engine.SuggestionCache.get(prev2, prev1, prefix)?.let { cached ->
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
                    com.noircipher.shade.engine.UserVocabularyInjector.inject(
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
                val strictStage1 = stage1.filter { it.startsWith(prefix, ignoreCase = true) }
                stage1.clear()
                stage1.addAll(strictStage1)

                val stage2 = if (prev1 != null) {
                    dict.rerankWithContext(null, prev1, stage1.toList())
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
                            touches = touches,
                            layout = layout,
                            contextWords = contextWords
                        )
                    }
                }

                // 4j — typed-word pipeline injections.
                // #1 — Multi-word phrase completion (good→morning, thank→you, ...).
                // Gated behind phraseSuggestionsOn: the bigram table contains
                // single-word completions (good→morning) AND multi-word ones
                // (good→"to see"); in word-by-word mode we still want the
                // single-word continuations, so we keep the source but filter
                // out space-containing entries.
                if (phraseSuggestionsOn) {
                    val phraseCompletions = com.noircipher.shade.engine.PhraseCompletion
                        .suggestPhraseCompletions(contextWords, prefix, limit = 3)
                    if (phraseCompletions.isNotEmpty()) {
                        ranked = (phraseCompletions + ranked).distinct()
                    }
                } else {
                    val phraseCompletions = com.noircipher.shade.engine.PhraseCompletion
                        .suggestPhraseCompletions(contextWords, prefix, limit = 3)
                        .filter { !it.contains(' ') }
                    if (phraseCompletions.isNotEmpty()) {
                        ranked = (phraseCompletions + ranked).distinct()
                    }
                }
                // #15 — Smart compose for common responses (ok → ok sounds good, ...).
                // These templates are inherently multi-word, so the whole source
                // is skipped in word-by-word mode.
                if (phraseSuggestionsOn && FieldTypeDetector.shouldSmartCompose(currentFieldType)) {
                    val smartResponses = com.noircipher.shade.engine.SmartResponseComposer.suggest(prefix, limit = 2)
                    if (smartResponses.isNotEmpty()) {
                        ranked = (smartResponses + ranked).distinct()
                    }
                }
                // #6+#7 — Cross-sentence context + sentence starters.
                val extendedContext = com.noircipher.shade.engine.CrossSentenceContext.extendContext(contextWords)
                val rawBeforeForSentence = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty()
                    .let { if (prefix.isNotEmpty() && it.endsWith(prefix, ignoreCase = true)) it.dropLast(prefix.length) else it }
                if (com.noircipher.shade.engine.CrossSentenceContext.isSentenceStart(extendedContext) ||
                    com.noircipher.shade.engine.CrossSentenceContext.endsWithSentenceTerminator(rawBeforeForSentence)) {
                    val starters = com.noircipher.shade.engine.CrossSentenceContext.sentenceStarters(prefix, limit = 2)
                    // In word-by-word mode drop multi-word starters ("let me",
                    // "thank you", "it would") and keep single-word ones ("the",
                    // "I", "how", ...).
                    val filteredStarters = if (phraseSuggestionsOn) starters
                        else starters.filter { !it.contains(' ') }
                    if (filteredStarters.isNotEmpty()) {
                        ranked = (filteredStarters + ranked).distinct()
                    }
                }
                // #18 — Greeting/time-aware bias.
                val timeWords = com.noircipher.shade.engine.GreetingTimeBias.timeAwareWords(prefix)
                if (timeWords.isNotEmpty()) {
                    ranked = (timeWords + ranked).distinct()
                }
                // #9 — Smart number prediction (after "in", "costs", "at", ...).
                val numberSuggestions = SuggestionHeuristics.numbersForContext(extendedContext, prefix)
                if (numberSuggestions.isNotEmpty()) {
                    ranked = (numberSuggestions + ranked).distinct()
                }
                // #17 — Emoji context prediction (after "love", "happy", ...).
                val emojiSuggestions = com.noircipher.shade.engine.EmojiContextPredictor.suggest(prev1, limit = 2)
                if (emojiSuggestions.isNotEmpty()) {
                    ranked = (emojiSuggestions + ranked).distinct()
                }
                // #25 — Neural next-word prediction: the RNN LM fills gaps when the
                // dictionary has no strong continuation for the previous words.
                if (prefix.isEmpty()) {
                    val nwpWords = nextWordScorer
                        ?.takeIf { it.isAvailable }
                        ?.suggest(contextWords, limit = 2)
                        .orEmpty()
                    if (nwpWords.isNotEmpty()) {
                        ranked = (ranked + nwpWords).distinct()
                    }
                }
                // #14 — Rejection-pattern learning: drop recently-rejected candidates.
                ranked = rejectionTracker.filter(ranked)
                // #5 — Prediction diversity (morphological): keep one variant per stem.
                ranked = com.noircipher.shade.engine.MorphologicalDiversity.deduplicate(ranked, limit = 6)
                // #20 — Per-app vocabulary snapshot.
                val appWords = perAppVocabSnapshot.wordsForPrefix(prefix, limit = 2)
                if (appWords.isNotEmpty()) {
                    ranked = (appWords + ranked).distinct()
                }
                // #22 — Confusable-pairs correction (their/there/they're, etc.).
                // Surface the context-corrected form when it isn't already ranked.
                com.noircipher.shade.engine.ConfusablePairs.correct(prefix, prev1)?.let { conf ->
                    if (ranked.none { it.equals(conf, ignoreCase = true) }) {
                        ranked = (listOf(conf) + ranked).distinct()
                    }
                }
                // #23 — Phonetic-match suggestions (for typo class that isn't a
                // keyboard slip but a pronunciation-based misspelling).
                if (ranked.size < 3) {
                    val phonetic = com.noircipher.shade.engine.PhoneticMatcher
                        .findPhoneticMatches(prefix, dict.suggest(prefix, 32), limit = 2)
                    if (phonetic.isNotEmpty()) {
                        ranked = (ranked + phonetic).distinct()
                    }
                }
                // #24 — Punctuation predictor: append a hint punctuation candidate
                // when the user has typed a recognized sentence ender.
                com.noircipher.shade.engine.PunctuationPredictor
                    .suggestPunctuation(contextWords)?.let { punct ->
                        if (ranked.none { it == punct }) {
                            ranked = (ranked + listOf(punct)).distinct().take(6)
                        }
                    }
                // #21 — Multi-word sentence completion ("How are you" → "doing today?").
                // Skipped entirely in word-by-word mode; in phrase mode keep only
                // the single-word completions ("soon", "later", "tomorrow", ...).
                run {
                    val sentenceCompletions = com.noircipher.shade.engine.SentenceCompleter
                        .suggest(contextWords, limit = 2)
                    val kept = if (phraseSuggestionsOn) sentenceCompletions
                        else sentenceCompletions.filter { !it.contains(' ') }
                    if (kept.isNotEmpty()) {
                        ranked = (kept + ranked).distinct()
                    }
                }
                // #11 — Suggestion cache: nothing to inject here, but record the
                // final ranked list so subsequent keystrokes can hit the cache.
                // #32 — Multi-armed bandit exploration: occasionally swap top-2
                // to learn whether the runner-up would have been accepted.
                if (onlineRankerTrainer.shouldExplore() && ranked.size > 2) {
                    val mutable = ranked.toMutableList()
                    val tmp = mutable[0]
                    mutable[0] = mutable[1]
                    mutable[1] = tmp
                    ranked = mutable
                }

                val shown = com.noircipher.shade.engine.SuggestionFinalizer.finalize(
                    prefix = prefix,
                    known = known,
                    grammarTop = if (blockOffensiveOn) grammarTop?.takeUnless {
                        com.noircipher.shade.engine.ProfanityFilter.isOffensive(it)
                    } else grammarTop,
                    ranked = filterOffensive(ranked),
                    phraseSuggestionsOn = phraseSuggestionsOn
                )
                // Cache the final list (after typo/grammar handling) so a cache hit
                // shows exactly what the full pipeline would.
                com.noircipher.shade.engine.SuggestionCache.put(prev2, prev1, prefix, shown)
                withContext(Dispatchers.Main.immediate) { setCandidates(shown) }
            }
        }
    }}

    internal fun centerBest(l: List<String>): List<String> = with(svc) {
        com.noircipher.shade.engine.SuggestionFinalizer.centerBest(l)
    }

    /**
     * Suggestion-level profanity gate ("block offensive words"). Removes
     * offensive candidates from what the bar shows or what autocorrect may
     * pick; the user's own typed word always passes untouched.
     */
    internal fun filterOffensive(candidates: List<String>): List<String> = with(svc) {
        if (blockOffensiveOn) candidates.filterNot {
            com.noircipher.shade.engine.ProfanityFilter.isOffensive(it)
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
        // If the picked candidate was the clipboard chip, clear it so it
        // doesn't reappear after the text is committed.
        if (lastClipText != null && w == lastClipText) lastClipText = null
        if (insightsAllowed()) typingStatsStore.recordSuggestionAccepted()
        // 4i — record acceptance for the acceptance-rate adapter + propagate
        // learning-rate multiplier to the online ranker; observe session
        // bigram; record sentence-position acceptance.
        if (personalLearningAllowed()) {
            acceptanceRateAdapter.recordAccepted()
            onlineRankerTrainer.setLearningRateMultiplier(acceptanceRateAdapter.learningRateMultiplier())
        }
        sessionBigramBoost.observeBigram(contextWordsExcluding(w).lastOrNull(), w)
        val pos = sentencePositionScorer.currentPosition(contextWordsExcluding(w))
        if (personalLearningAllowed()) {
            sentencePositionScorer.recordAcceptance(pos)
        }
        val prevWords = contextWordsExcluding(w)
        val prev1 = prevWords.lastOrNull()
        val prev2 = prevWords.dropLast(1).lastOrNull()
        val typed = composing.toString().ifEmpty { currentTypedWordFromField() }

        if (learnOn && personalLearningAllowed()) {
            userWordMemory.bump(w)
        }
        if (adaptiveLearningAllowed()) {
            adaptiveWordMemory.record(w)
        }
        if (appContextLearningAllowed()) {
            appContextModel.recordWord(w)
        }
        if (rankerLearningAllowed() || calibratorLearningAllowed()) {
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
        if (w.isNotEmpty() && w[0].isLetter()) {
            if (learnOn && personalLearningAllowed() && canCompose) {
                dict.learn(w, prev2 = prev2, prev1 = prev1)
            }
        }
        lastSpaceTime = 0L
        afterText()
    }}

}