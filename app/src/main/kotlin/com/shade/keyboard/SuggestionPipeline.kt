package com.shade.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.shade.keyboard.engine.CandidateRanker
import com.shade.keyboard.engine.GrammarEngine
import com.shade.keyboard.engine.HeuristicSuggestionEngine
import com.shade.keyboard.engine.NeuralPredictionEngine
import com.shade.keyboard.engine.RankingFeatureExtractor
import com.shade.keyboard.motion.ShadeMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SuggestionPipeline(private val svc: ShadeService) {
    private fun isWordChar(c: Char): Boolean {
        if (c.isLetterOrDigit() || c == '\'' || c == '-') return true
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
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
        val prev1 = contextWords.lastOrNull()
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
        // Atomic reference swap: clear()+putAll() on the LIVE map exposed an empty
        // window to Main-thread pickSuggestion (positive features lost, acceptance
        // training silently skipped).
        lastRankedFeatures = newRankedFeatures
        lastRawRankerScores = newRawScores
        return rescored.sortedByDescending { it.second }.map { it.first } + candidates.drop(8)
    }}

    /** One in-flight engine reload at a time: without this guard every keystroke
     *  during a multi-second load launched ANOTHER concurrent reload of the same
     *  engine (neuralLocale only updates after the load completes). */
    @Volatile private var neuralReloadTarget: String? = null

    private fun ensureNeuralLocale(desired: String) { with(svc) {
        if (neuralLocale == desired || neuralReloadTarget == desired) return
        neuralReloadTarget = desired
        scope.launch(Dispatchers.Default) {
            try { reloadNeuralEngine(desired) } finally { neuralReloadTarget = null }
        }
    }}

    private fun ensureFinalShownFeatures(
        shown: List<String>,
        prefix: String,
        prev1: String?
    ) { with(svc) {
        val missing = shown.take(3).filter { it.isNotEmpty() && !lastRankedFeatures.containsKey(it) }
        if (missing.isEmpty()) return@with
        val addF = HashMap<String, FloatArray>(missing.size)
        val addS = HashMap<String, Float>(missing.size)
        for (word in missing) {
            val features = RankingFeatureExtractor.extract(
                candidate = word,
                typedWord = prefix,
                typeCode = RankingFeatureExtractor.TYPE_WORD,
                userUnigramCount = userWordMemory.count(word),
                userBigramCount = sessionBigramBoost.bigramCount(prev1, word)
            )
            addF[word] = features
            addS[word] = runCatching { rankingScorer?.score(features) ?: 0f }.getOrDefault(0f)
        }
        // Copy-on-write: a published map is never mutated in place.
        lastRankedFeatures = lastRankedFeatures + addF
        lastRawRankerScores = lastRawRankerScores + addS
    }}

    internal fun updateSuggestions() { with(svc) {
        if (!svc.slotsReady()) return
        suggestJob?.cancel()
        suggestDebounceJob?.cancel()
        if (suggestionsOn == false || suggestionsAllowed == false) {
            setCandidates(emptyList())
            return
        }
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
        val beforeCursor = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString() ?: ""
        val urlDetection = UrlEmailDetector.detect(typedWord, beforeCursor)
        if (UrlEmailDetector.suppressSuggestions(urlDetection)) {
            setCandidates(emptyList())
            return
        }
        if (typedWord.isEmpty()) {
            val contextWords = contextWordsExcluding("")
            val desiredLocale = desiredNeuralLocale(contextWords)
            ensureNeuralLocale(desiredLocale)
            val prev1 = contextWords.lastOrNull()
            val prev2 = contextWords.dropLast(1).lastOrNull()
            val context = neuralContext("")
            suggestDebounceJob = scope.launch {
                delay(ShadeService.SUGGEST_DEBOUNCE_MS)
                if (!isActive) return@launch
                suggestJob = scope.launch(Dispatchers.Default) {
                    val neuralNext = withContext(Dispatchers.Default) {
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
        ensureNeuralLocale(desiredLocale)
        val prev1 = contextWords.lastOrNull()
        val prev2 = contextWords.dropLast(1).lastOrNull()
        val touches = if (composingActive) touchTrace.last(touchTrace.size()) else emptyList()
        val layout = kbKeyLayout()
        val neuralCtx = neuralContext(typedWord)
        suggestDebounceJob = scope.launch {
            delay(ShadeService.SUGGEST_DEBOUNCE_MS)
            if (!isActive) return@launch
            suggestJob = scope.launch(Dispatchers.Default) {
                // S4 — capture the salt: a cancelled sibling pipeline can overwrite the
                // global between our get and put, filing this result under the wrong
                // app/locale/incognito context. Re-check before writing.
                val cacheSalt = "$configuredLocale|$incognito|" +
                    "${currentAppPackage().orEmpty()}|$phraseSuggestionsOn|$blockOffensiveOn|$learningGeneration"
                com.shade.keyboard.engine.SuggestionCache.salt = cacheSalt
                com.shade.keyboard.engine.SuggestionCache.get(prev2, prev1, prefix)?.let { cached ->
                    ensureFinalShownFeatures(cached, prefix, prev1)
                    withContext(Dispatchers.Main.immediate) { setCandidates(cached) }
                    return@launch
                }
                val known = dict.contains(prefix)
                val stage1 = LinkedHashSet<String>()
                val effectivePrefix = prefix
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
                val seenLower = HashSet<String>()
                val strictStage1 = stage1.filter {
                    it.startsWith(prefix, ignoreCase = true) && seenLower.add(it.lowercase())
                }
                stage1.clear()
                stage1.addAll(strictStage1)
                val stage2 = if (prev1 != null) {
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
                val injections = ArrayList<Pair<Float, List<String>>>(8)
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
                if (phraseSuggestionsOn && FieldTypeDetector.shouldSmartCompose(currentFieldType)) {
                    val smartResponses = com.shade.keyboard.engine.SmartResponseComposer.suggest(prefix, limit = 2)
                    if (smartResponses.isNotEmpty()) injections.add(0.85f to smartResponses)
                }
                val extendedContext = com.shade.keyboard.engine.CrossSentenceContext.extendContext(contextWords)
                val rawBeforeForSentence = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString().orEmpty()
                    .let { if (prefix.isNotEmpty() && it.endsWith(prefix, ignoreCase = true)) it.dropLast(prefix.length) else it }
                if (com.shade.keyboard.engine.CrossSentenceContext.isSentenceStart(extendedContext) ||
                    com.shade.keyboard.engine.CrossSentenceContext.endsWithSentenceTerminator(rawBeforeForSentence)) {
                    val starters = com.shade.keyboard.engine.CrossSentenceContext.sentenceStarters(prefix, limit = 2)
                    val filteredStarters = if (phraseSuggestionsOn) starters
                    else starters.filter { !it.contains(' ') }
                    if (filteredStarters.isNotEmpty()) injections.add(0.70f to filteredStarters)
                }
                val timeWords = com.shade.keyboard.engine.GreetingTimeBias.timeAwareWords(prefix)
                if (timeWords.isNotEmpty()) injections.add(0.60f to timeWords)
                val numberSuggestions = SuggestionHeuristics.numbersForContext(extendedContext, prefix)
                if (numberSuggestions.isNotEmpty()) injections.add(0.75f to numberSuggestions)
                val emojiSuggestions = com.shade.keyboard.engine.EmojiContextPredictor.suggest(prev1, limit = 2)
                if (emojiSuggestions.isNotEmpty()) injections.add(0.50f to emojiSuggestions)
                if (prefix.isEmpty()) {
                    val nwpWords = nextWordScorer
                        ?.takeIf { it.isAvailable }
                        ?.suggest(contextWords, limit = 2)
                        .orEmpty()
                    if (nwpWords.isNotEmpty()) injections.add(0.70f to nwpWords)
                }
                ranked = rejectionTracker.filter(ranked)
                var prependedFix: String? = null
                val appWords = perAppVocabSnapshot.wordsForPrefix(prefix, limit = 2)
                if (appWords.isNotEmpty()) injections.add(0.80f to appWords)
                com.shade.keyboard.engine.ConfusablePairs.correct(prefix, prev1)?.let { conf ->
                    if (ranked.none { it.equals(conf, ignoreCase = true) }) {
                        ranked = (listOf(conf) + ranked).distinct()
                        prependedFix = conf
                    }
                }
                if (ranked.size < 3) {
                    val phonetic = com.shade.keyboard.engine.PhoneticMatcher
                        .findPhoneticMatches(prefix, dict.suggest(prefix, 32), limit = 2)
                    if (phonetic.isNotEmpty()) {
                        ranked = (ranked + phonetic).distinct()
                    }
                }
                com.shade.keyboard.engine.PunctuationPredictor
                    .suggestPunctuation(contextWords)?.let { punct ->
                        if (ranked.none { it == punct }) {
                            ranked = (ranked + listOf(punct)).distinct().take(6)
                        }
                    }
                run {
                    val sentenceCompletions = com.shade.keyboard.engine.SentenceCompleter
                        .suggest(contextWords, limit = 2)
                    val kept = if (phraseSuggestionsOn) sentenceCompletions
                    else sentenceCompletions.filter { !it.contains(' ') }
                    if (kept.isNotEmpty()) injections.add(0.85f to kept)
                }
                if (!known && prefix.length >= 3) {
                    barSpellFix(prefix, prev1)?.let { fix ->
                        if (ranked.none { it.equals(fix, ignoreCase = true) }) {
                            ranked = (listOf(fix) + ranked).distinct()
                            prependedFix = fix
                        }
                    }
                }
                val mergedScores = com.shade.keyboard.engine.SuggestionFinalizer
                    .mergeScoredWithScores(ranked, injections)
                ranked = mergedScores.keys.toList()
                ranked = rejectionTracker.filter(ranked)
                ranked = com.shade.keyboard.engine.MorphologicalDiversity.deduplicate(ranked, limit = 6)
                // S3 — exploration exists to learn which candidate wins; in incognito or
                // learning-off sessions nothing is learned, so shuffling the bar only
                // degrades suggestions for no data.
                if (personalLearningAllowed() && rankerLearningAllowed() &&
                    onlineRankerTrainer.shouldExplore() && ranked.size > 2
                ) {
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
                ensureFinalShownFeatures(shown, prefix, prev1)
                if (com.shade.keyboard.engine.SuggestionCache.salt == cacheSalt) {
                    com.shade.keyboard.engine.SuggestionCache.put(prev2, prev1, prefix, shown)
                }
                withContext(Dispatchers.Main.immediate) { setCandidates(shown) }
            }
        }
    }}

    internal fun centerBest(l: List<String>): List<String> = with(svc) {
        com.shade.keyboard.engine.SuggestionFinalizer.centerBest(l)
    }

    internal fun filterOffensive(candidates: List<String>): List<String> = with(svc) {
        if (blockOffensiveOn) candidates.filterNot {
            com.shade.keyboard.engine.ProfanityFilter.isOffensive(it)
        } else candidates
    }

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
        val style = currentSuggestionStyle()
        return raw.map { SuggestionCasing.apply(it, style, lastClipText) }
    }}

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

    internal fun resetCandidates() { with(svc) {
        candidates = emptyList()
        shownWords = emptyList()
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
        val display = casedCandidates(effective)
        val rawChanged = effective != candidates
        if (!rawChanged && display == shownWords) return
        if (rawChanged) {
            if (insightsAllowed() && effective.isNotEmpty()) typingStatsStore.recordSuggestionShown()
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
        val shown = shownWords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: w
        val wasClipboardChip = lastClipText != null && w == lastClipText
        if (wasClipboardChip) lastClipText = null
        if (insightsAllowed()) typingStatsStore.recordSuggestionAccepted()
        val typed = composing.toString().ifEmpty { currentTypedWordFromField() }
        val prevWords = contextWordsExcluding(typed)
        val prev1 = prevWords.lastOrNull()
        val prev2 = prevWords.dropLast(1).lastOrNull()
        val learnable = isLearnableWord(w) && !wasClipboardChip
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
            if (userWordMemory.score(w) <= 0f) learningGeneration++
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
        clearCorrectionEvent()
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
        if (learnable && learnOn && personalLearningAllowed() && canCompose) {
            dict.learn(w, prev2 = prev2, prev1 = prev1)
        }
        lastSpaceTime = 0L
        afterText()
    }}
}
