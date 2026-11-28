package com.shade.keyboard.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEnginePolicyTest {

    @Test
    fun prefersTheOnDeviceEngineWhenAvailableAndWanted() {
        assertEquals(
            VoiceEnginePolicy.ENGINE_ON_DEVICE,
            VoiceEnginePolicy.chooseEngine(sdkInt = 33, onDeviceAvailable = true, preferOnDevice = true)
        )
    }

    @Test
    fun fallsBackWhenUnavailableOrUnwantedOrTooOld() {
        assertEquals(
            VoiceEnginePolicy.ENGINE_STANDARD,
            VoiceEnginePolicy.chooseEngine(sdkInt = 33, onDeviceAvailable = false, preferOnDevice = true)
        )
        assertEquals(
            VoiceEnginePolicy.ENGINE_STANDARD,
            VoiceEnginePolicy.chooseEngine(sdkInt = 33, onDeviceAvailable = true, preferOnDevice = false)
        )
        assertEquals(
            VoiceEnginePolicy.ENGINE_STANDARD,
            VoiceEnginePolicy.chooseEngine(sdkInt = 29, onDeviceAvailable = true, preferOnDevice = true)
        )
    }

    @Test
    fun finalTextPrefersFinalsOverPartials() {
        assertEquals("hello world", VoiceEnginePolicy.pickFinalText(listOf("hello world", "hello"), "hello wor"))
        assertEquals("hello wor", VoiceEnginePolicy.pickFinalText(null, "hello wor"))
        assertEquals("hello wor", VoiceEnginePolicy.pickFinalText(emptyList(), "hello wor"))
        assertEquals("", VoiceEnginePolicy.pickFinalText(listOf("   "), "   "))
    }

    @Test
    fun hudTextIsTrimmedAndBounded() {
        assertEquals("hello", VoiceEnginePolicy.hudText("  hello "))
        assertEquals("", VoiceEnginePolicy.hudText(null))
        assertEquals("", VoiceEnginePolicy.hudText("    "))
        val long = "a".repeat(300)
        assertEquals(120, VoiceEnginePolicy.hudText(long).length)
    }

    @Test
    fun knownErrorCodesGetHumanReadableDescriptions() {
        assertEquals("network error", VoiceEnginePolicy.describeError(2))          // ERROR_NETWORK
        assertEquals("no speech detected", VoiceEnginePolicy.describeError(6))     // ERROR_SPEECH_TIMEOUT
        assertEquals("recognizer busy", VoiceEnginePolicy.describeError(8))        // ERROR_RECOGNIZER_BUSY
        assertEquals(
            "microphone permission missing",
            VoiceEnginePolicy.describeError(9)                                     // ERROR_INSUFFICIENT_PERMISSIONS
        )
    }

    @Test
    fun unknownErrorCodesFallBackToGenericText() {
        assertEquals("voice error 99", VoiceEnginePolicy.describeError(99))
        assertEquals("voice error -1", VoiceEnginePolicy.describeError(-1))
    }

    // ------------------------------------------------------------------ recovery

    private fun recover(
        usedOnDevice: Boolean,
        error: Int,
        attempts: Int = 0,
        standard: Boolean = true,
        onDevice: Boolean = true,
        wanted: Boolean = true
    ) = VoiceEnginePolicy.recoveryAction(
        usedOnDevice, error, attempts, standard, onDevice, wanted
    )

    @Test
    fun languageErrorOnDeviceSwitchesToStandard() {
        // 10 = ERROR_LANGUAGE_UNAVAILABLE, 11 = ERROR_LANGUAGE_NOT_SUPPORTED
        assertEquals(
            VoiceEnginePolicy.ErrorAction.SWITCH_TO_STANDARD,
            recover(usedOnDevice = true, error = 10)
        )
        assertEquals(
            VoiceEnginePolicy.ErrorAction.SWITCH_TO_STANDARD,
            recover(usedOnDevice = true, error = 11)
        )
    }

    @Test
    fun languageErrorWithoutStandardEngineReports() {
        assertEquals(
            VoiceEnginePolicy.ErrorAction.REPORT,
            recover(usedOnDevice = true, error = 10, standard = false)
        )
    }

    @Test
    fun transientErrorRetriesTheSameEngine() {
        // 8 = ERROR_RECOGNIZER_BUSY, 5 = ERROR_CLIENT
        assertEquals(VoiceEnginePolicy.ErrorAction.RETRY, recover(usedOnDevice = true, error = 8))
        assertEquals(VoiceEnginePolicy.ErrorAction.RETRY, recover(usedOnDevice = false, error = 5))
    }

    @Test
    fun networkErrorOnStandardSwitchesToOnDeviceWhenWanted() {
        // 2 = ERROR_NETWORK
        assertEquals(
            VoiceEnginePolicy.ErrorAction.SWITCH_TO_ON_DEVICE,
            recover(usedOnDevice = false, error = 2)
        )
        // Not wanted (settings toggle off) or not available: plain retry.
        assertEquals(
            VoiceEnginePolicy.ErrorAction.RETRY,
            recover(usedOnDevice = false, error = 2, wanted = false)
        )
        assertEquals(
            VoiceEnginePolicy.ErrorAction.RETRY,
            recover(usedOnDevice = false, error = 2, onDevice = false)
        )
    }

    @Test
    fun hardFailureOnDeviceFallsBackToStandard() {
        // 3 = ERROR_AUDIO — not transient, not a language error
        assertEquals(
            VoiceEnginePolicy.ErrorAction.SWITCH_TO_STANDARD,
            recover(usedOnDevice = true, error = 3)
        )
        // The standard engine failing a hard error has no alternative below it.
        assertEquals(VoiceEnginePolicy.ErrorAction.REPORT, recover(usedOnDevice = false, error = 3))
    }

    @Test
    fun attemptBudgetStopsPingPong() {
        assertEquals(
            VoiceEnginePolicy.ErrorAction.REPORT,
            recover(usedOnDevice = true, error = 10, attempts = 2)
        )
        assertEquals(
            VoiceEnginePolicy.ErrorAction.RETRY,
            recover(usedOnDevice = true, error = 8, attempts = 1)
        )
    }
}
