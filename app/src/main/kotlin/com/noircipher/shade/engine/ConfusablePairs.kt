package com.noircipher.shade.engine

/**
 * Detects common confusable word pairs and suggests the right one based on
 * context.
 *
 * Examples: `there`/`their`/`they're`, `your`/`you're`, `its`/`it's`,
 * `to`/`too`/`two`, `then`/`than`, `affect`/`effect`, `loose`/`lose`.
 *
 * [suggestConfusable] returns the corrected form when the previous word (or
 * absence of one) clearly indicates which spelling the user meant.  Returns
 * `null` when the context is ambiguous so the caller falls through to the
 * default suggestion path.
 */
object ConfusablePairs {

    private data class Confusable(
        val word: String,
        val alternatives: List<String>,
        /** Returns the suggested replacement, or null when ambiguous. */
        val resolver: (prev: String?) -> String?
    )

    private val GROUPS: List<Confusable> = listOf(
        Confusable("there", listOf("their", "they're")) { prev ->
            // "there" = location/existence; "their" = possession; "they're" = they are
            when {
                prev == null -> "there"
                prev == "is" || prev == "are" || prev == "was" || prev == "were" ||
                    prev == "over" || prev == "right" || prev == "go" || prev == "down" -> "there"
                prev == "that" || prev == "this" || prev == "what" || prev == "whose" ||
                    prev == "is" || prev == "are" -> "there"
                else -> null
            }
        },
        Confusable("their", listOf("there", "they're")) { prev ->
            // Possession usually follows a verb or preposition: "to their", "with their"
            when {
                prev == null -> null
                prev == "to" || prev == "with" || prev == "of" || prev == "for" ||
                    prev == "from" || prev == "by" || prev == "and" -> "their"
                else -> null
            }
        },
        Confusable("they're", listOf("there", "their")) { prev ->
            // "they're going", "they're here"
            when {
                prev == null -> null
                prev == "maybe" || prev == "perhaps" || prev == "guess" -> "they're"
                else -> null
            }
        },
        Confusable("your", listOf("you're")) { prev ->
            // Possession: "is your", "to your", "with your"
            when {
                prev == null -> "your"
                prev == "is" || prev == "are" || prev == "was" || prev == "were" ||
                    prev == "to" || prev == "with" || prev == "for" || prev == "of" ||
                    prev == "on" || prev == "in" || prev == "by" -> "your"
                else -> null
            }
        },
        Confusable("you're", listOf("your")) { prev ->
            // "you're going", "you're right"
            when {
                prev == null -> null
                prev == "guess" || prev == "think" || prev == "say" || prev == "maybe" -> "you're"
                else -> null
            }
        },
        Confusable("its", listOf("it's")) { prev ->
            // Possession: "the dog and its", "on its"
            when {
                prev == null -> "its"
                prev == "the" || prev == "a" || prev == "an" || prev == "this" ||
                    prev == "that" || prev == "every" || prev == "on" || prev == "with" -> "its"
                prev == "see" || prev == "know" || prev == "think" || prev == "guess" ||
                    prev == "looks" || prev == "seems" -> "it's"
                else -> null
            }
        },
        Confusable("it's", listOf("its")) { prev ->
            when {
                prev == null -> "it's"
                prev == "guess" || prev == "think" || prev == "know" || prev == "looks" -> "it's"
                else -> null
            }
        },
        Confusable("to", listOf("too", "two")) { prev ->
            // "going to", "want to", "have to"
            when {
                prev == null -> "to"
                prev == "going" || prev == "want" || prev == "have" || prev == "need" ||
                    prev == "have" || prev == "got" || prev == "going" || prev == "want" ||
                    prev == "try" || prev == "trying" || prev == "used" || prev == "going" ||
                    prev == "go" || prev == "come" || prev == "listen" || prev == "talk" -> "to"
                prev == "me" || prev == "you" || prev == "i" || prev == "she" || prev == "he" -> "too"
                else -> "to"
            }
        },
        Confusable("too", listOf("to", "two")) { prev ->
            // "me too", "you too", "much too", "really too"
            when {
                prev == null -> null
                prev == "me" || prev == "you" || prev == "i" || prev == "we" ||
                    prev == "they" || prev == "him" || prev == "her" || prev == "us" -> "too"
                prev == "much" || prev == "really" || prev == "way" -> "too"
                else -> null
            }
        },
        Confusable("then", listOf("than")) { prev ->
            // "then" = time sequence; "than" = comparison
            when {
                prev == null -> "then"
                prev == "and" || prev == "since" || prev == "now" || prev == "then" ||
                    prev == "well" || prev == "so" || prev == "ok" || prev == "but" -> "then"
                prev == "rather" || prev == "more" || prev == "less" || prev == "better" ||
                    prev == "worse" || prev == "faster" || prev == "slower" || prev == "bigger" -> "than"
                else -> "then"
            }
        },
        Confusable("than", listOf("then")) { prev ->
            when {
                prev == null -> null
                prev == "rather" || prev == "more" || prev == "less" || prev == "better" ||
                    prev == "worse" || prev == "other" -> "than"
                else -> null
            }
        },
        Confusable("affect", listOf("effect")) { prev ->
            // "affect" = verb; "effect" = noun
            when {
                prev == null -> null
                prev == "will" || prev == "to" || prev == "can" || prev == "may" ||
                    prev == "might" || prev == "could" || prev == "should" || prev == "would" -> "affect"
                prev == "the" || prev == "an" || prev == "no" || prev == "any" ||
                    prev == "some" || prev == "this" || prev == "that" -> "effect"
                else -> null
            }
        },
        Confusable("effect", listOf("affect")) { prev ->
            when {
                prev == null -> "effect"
                prev == "the" || prev == "an" || prev == "no" || prev == "any" ||
                    prev == "some" || prev == "this" || prev == "that" || prev == "side" -> "effect"
                else -> null
            }
        },
        Confusable("loose", listOf("lose")) { prev ->
            when {
                prev == null -> null
                prev == "too" || prev == "very" || prev == "pretty" || prev == "rather" -> "loose"
                prev == "will" || prev == "to" || prev == "didn't" || prev == "won't" ||
                    prev == "can't" || prev == "going" || prev == "might" -> "lose"
                else -> null
            }
        },
        Confusable("lose", listOf("loose")) { prev ->
            when {
                prev == null -> "lose"
                prev == "will" || prev == "to" || prev == "won't" || prev == "didn't" ||
                    prev == "can't" || prev == "might" || prev == "could" -> "lose"
                else -> null
            }
        },
        Confusable("whose", listOf("who's")) { prev ->
            when {
                prev == null -> "whose"
                prev == "anyone" || prev == "someone" || prev == "everyone" ||
                    prev == "nobody" || prev == "people" -> "whose"
                prev == "guess" || prev == "know" || prev == "wonder" -> "who's"
                else -> null
            }
        },
        Confusable("who's", listOf("whose")) { prev ->
            when {
                prev == null -> null
                prev == "guess" || prev == "know" || prev == "wonder" -> "who's"
                else -> null
            }
        },
        Confusable("were", listOf("where", "we're")) { prev ->
            when {
                prev == null -> null
                prev == "we" || prev == "they" || prev == "you" || prev == "there" -> "were"
                prev == "anywhere" || prev == "somewhere" || prev == "nowhere" ||
                    prev == "everywhere" || prev == "from" || prev == "to" -> "where"
                else -> null
            }
        },
        Confusable("where", listOf("were", "we're")) { prev ->
            when {
                prev == null -> "where"
                prev == "anywhere" || prev == "somewhere" || prev == "nowhere" ||
                    prev == "everywhere" || prev == "from" || prev == "to" ||
                    prev == "know" || prev == "wonder" -> "where"
                else -> null
            }
        }
    )

    private val BY_WORD: Map<String, Confusable> = GROUPS.associateBy { it.word }

    /**
     * If [word] is one of the confusable homophones, returns the right one
     * based on the [previousWord] (or null if the previous word was a sentence
     * start).  Returns null when no correction is needed or the context is
     * ambiguous.
     */
    fun suggestConfusable(word: String, previousWord: String?): String? {
        val lower = word.lowercase().trim()
        val c = BY_WORD[lower] ?: return null
        return c.resolver(previousWord?.lowercase()?.trim()?.takeIf { it.isNotEmpty() })
    }
}
