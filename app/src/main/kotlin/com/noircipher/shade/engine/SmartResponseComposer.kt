package com.noircipher.shade.engine

/**
 * Suggests common multi-token responses based on a short preceding word or
 * phrase.
 *
 * Examples:
 *   "ok"     → "ok, sounds good"
 *   "thanks" → "thanks, I appreciate it"
 *   "sure"   → "sure, no problem"
 *
 * Designed as a *non-intrusive* helper: callers should only surface these
 * suggestions when the user has just committed the trigger word AND there is
 * room in the suggestion bar — never as autocorrect replacements.
 */
object SmartResponseComposer {

    private data class Trigger(val keyword: String, val response: String)

    private val TRIGGERS: List<Trigger> = listOf(
        Trigger("ok", "ok, sounds good"),
        Trigger("okay", "okay, sounds good"),
        Trigger("k", "ok, sounds good"),
        Trigger("thanks", "thanks, I appreciate it"),
        Trigger("thank", "thank you"),
        Trigger("thx", "thanks, appreciate it"),
        Trigger("sure", "sure, no problem"),
        Trigger("yes", "yes, of course"),
        Trigger("yeah", "yeah, sounds good"),
        Trigger("yep", "yep, that works"),
        Trigger("yup", "yup, sounds good"),
        Trigger("no", "no, not really"),
        Trigger("nope", "nope, sorry"),
        Trigger("hi", "hi, how are you?"),
        Trigger("hello", "hello, how are you?"),
        Trigger("hey", "hey, what's up?"),
        Trigger("sup", "what's up?"),
        Trigger("sorry", "sorry, my bad"),
        Trigger("apologies", "apologies for the delay"),
        Trigger("bye", "bye, talk soon"),
        Trigger("goodbye", "goodbye, take care"),
        Trigger("later", "talk to you later"),
        Trigger("cu", "see you later"),
        Trigger("cool", "cool, let's do it"),
        Trigger("great", "great, thanks"),
        Trigger("awesome", "awesome, thanks!"),
        Trigger("perfect", "perfect, thanks"),
        Trigger("sounds", "sounds good"),
        Trigger("agree", "agreed"),
        Trigger("wow", "wow, that's great"),
        Trigger("lol", "lol, that's funny"),
        Trigger("haha", "haha, that's great"),
        Trigger("sorry", "sorry about that"),
        Trigger("wait", "wait, let me check"),
        Trigger("actually", "actually, let me think"),
        Trigger("maybe", "maybe later"),
        Trigger("tomorrow", "tomorrow works"),
        Trigger("today", "today works for me"),
        Trigger("tonight", "tonight works"),
        Trigger("good", "good to hear"),
        Trigger("nice", "nice, thanks"),
        Trigger("got", "got it, thanks"),
        Trigger("will", "will do"),
        Trigger("let", "let me check"),
        Trigger("lmao", "lol, that's hilarious"),
        Trigger("omg", "omg, that's wild")
    )

    private val BY_KEYWORD: Map<String, String> = TRIGGERS.associate { it.keyword to it.response }

    /**
     * Returns a smart response suggestion for the given [trigger] word, or
     * null when no template matches.
     */
    fun suggest(trigger: String): String? {
        if (trigger.isEmpty()) return null
        return BY_KEYWORD[trigger.lowercase()]
    }
}
