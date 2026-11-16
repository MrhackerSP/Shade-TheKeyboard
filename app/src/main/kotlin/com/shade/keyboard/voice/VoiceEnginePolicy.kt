package com.shade.keyboard.voice

/**
 * Pure decision helpers for the voice pipeline, kept free of Android types so
 * they can be unit-tested on the JVM.
 */
object VoiceEnginePolicy {
    /** Offline recognizer bundled with the OS (no network, no cloud round trip). */
    const val ENGINE_ON_DEVICE = 0

    /** Platform speech service; may use the network depending on the device. */
    const val ENGINE_STANDARD = 1

    /** Lowest API level that ships the on-device recognizer API. */
    const val ON_DEVICE_MIN_SDK = 31

    /**
     * Picks the engine to try first. The on-device recognizer is used when the
     * user prefers it, the OS is new enough, and the device actually ships an
     * offline implementation; otherwise the standard service is used. The caller
     * must still fall back at runtime if creating the recognizer fails.
     */
    fun chooseEngine(sdkInt: Int, onDeviceAvailable: Boolean, preferOnDevice: Boolean): Int =
        if (preferOnDevice && sdkInt >= ON_DEVICE_MIN_SDK && onDeviceAvailable) {
            ENGINE_ON_DEVICE
        } else {
            ENGINE_STANDARD
        }

    /**
     * Final text for a finished recognition: the best final result, else the
     * last live partial (some engines only emit partials before an error).
     */
    fun pickFinalText(finals: List<String>?, lastPartial: String?): String {
        val best = finals?.firstOrNull()?.trim().orEmpty()
        if (best.isNotEmpty()) return best
        return lastPartial?.trim().orEmpty()
    }

    /** Text shown in the live listening chip: trimmed and bounded. */
    fun hudText(partial: String?): String = partial?.trim()?.take(120).orEmpty()
}
