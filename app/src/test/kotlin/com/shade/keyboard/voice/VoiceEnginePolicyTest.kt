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
}
