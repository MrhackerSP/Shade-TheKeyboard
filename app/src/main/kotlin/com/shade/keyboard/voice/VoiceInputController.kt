package com.shade.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Voice input session manager.
 *
 * Recognizer lifecycle rules that keep the "recognizer error" chip away:
 *  - every SpeechRecognizer call happens on the main thread;
 *  - each attempt gets a FRESH recognizer (a parked one throws BUSY/CLIENT on
 *    the next startListening) and is destroyed on result, error and cancel;
 *  - the on-device recognizer is preferred when the platform exposes one, so
 *    sessions work without a reachable network speech server;
 *  - transient failures (busy / client / network / server) retry exactly once;
 *  - silence and no-match are reported as guidance, never as an error code.
 */
class VoiceInputController(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onVoiceError: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var retries = 0
    private var preferOnDevice = false

    fun start() {
        main.post {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    context.startActivity(
                        Intent(context, VoicePermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                onVoiceError("Microphone access is off — allow it, then tap the mic again")
                return@post
            }
            val onDevice = onDeviceAvailable()
            if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
                onVoiceError("No speech recognizer is available on this device")
                return@post
            }
            preferOnDevice = onDevice
            retries = 0
            beginSession()
        }
    }

    fun cancel() {
        main.post { teardown() }
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false)

    private fun teardown() {
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
    }

    private fun beginSession() {
        teardown()
        val r = runCatching {
            if (preferOnDevice && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.recoverCatching {
            SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (r == null) {
            onVoiceError("Speech service could not start — tap the mic to retry")
            return
        }
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                prefs.getString("voice_language", null) ?: Locale.getDefault().toLanguageTag()
            )
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { r.startListening(intent) }.onFailure {
            teardown()
            onVoiceError("Speech service could not start — tap the mic to retry")
        }
    }

    private fun first(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = first(partialResults)
            if (t.isNotEmpty()) main.post { onPartialText(t) }
        }

        override fun onResults(results: Bundle?) {
            main.post {
                val t = first(results)
                teardown()
                if (t.isNotEmpty()) onFinalText(t)
                else onVoiceError("Didn't catch that — tap the mic and speak again")
            }
        }

        override fun onError(error: Int) {
            main.post {
                val transient = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_SERVER
                if (transient && retries < 1) {
                    retries++
                    // A network/server failure on the network recognizer is exactly
                    // when the on-device one (if present) should take over.
                    if (error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_SERVER
                    ) {
                        preferOnDevice = preferOnDevice || onDeviceAvailable()
                    }
                    beginSession()
                    return@post
                }
                teardown()
                onVoiceError(
                    when (error) {
                        SpeechRecognizer.ERROR_AUDIO ->
                            "Microphone is busy — close apps using it and retry"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone access is off — allow it, then tap again"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "Didn't catch that — tap the mic and speak again"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech needs a network connection here — connect and retry"
                        else -> "Speech service hiccup — tap the mic to retry"
                    }
                )
            }
        }
    }
}
