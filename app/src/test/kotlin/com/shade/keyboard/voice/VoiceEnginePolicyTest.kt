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
}
