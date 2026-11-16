package com.shade.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import com.shade.keyboard.R

/**
 * Voice typing controller.
 *
 * Two engines are supported:
 *  - the OS's on-device recognizer (API 31+, runs fully offline, no audio leaves
 *    the phone) - preferred when the user has not turned it off and the device
 *    ships an implementation;
 *  - the platform speech service as a fallback (its behaviour is device-specific).
 *
 * Live partial results are streamed to [onPartialText] while listening so the
 * keyboard can show the words as they are recognized; the finished text goes to
 * [onFinalText]. If the engine produces no final result, the last partial is
 * promoted so a long dictation is never silently dropped.
 */
class VoiceInputController(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onVoiceError: (String) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var lastPartial: String? = null
    var isListening = false
        private set

    /** True when the active session uses the offline recognizer. */
    var usesOnDeviceEngine = false
        private set

    // Session state for the fast-fail retry (see onError): language, when this engine
    // attempt started, and how many retries were used.
    private var sessionLanguage: String? = null
    private var attemptStartedAtMs = 0L
    private var retryCount = 0

    fun start(languageTag: String? = null) {
        sessionLanguage = languageTag
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onPartialText("") // never leave the "Listening…" chip up without a session
            Toast.makeText(context, R.string.voice_input_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onPartialText("") // the chip showed "Listening…" — the session never started
            Toast.makeText(context, R.string.voice_permission_needed, Toast.LENGTH_LONG).show()
            try {
                // A keyboard can't show the permission dialog itself; a tiny transparent activity does.
                context.startActivity(
                    Intent(context, VoicePermissionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                // Some restricted contexts cannot start activities.
                Log.w(TAG, "Failed to launch voice permission activity", e)
            }
            return
        }

        cancel()
        retryCount = 0
        startEngine(preferOnDevice())
    }

    private fun preferOnDevice(): Boolean {
        val prefer = prefs.getBoolean(PREF_ON_DEVICE, true)
        val onDeviceOk = Build.VERSION.SDK_INT >= VoiceEnginePolicy.ON_DEVICE_MIN_SDK &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return VoiceEnginePolicy.chooseEngine(
            Build.VERSION.SDK_INT, onDeviceOk, prefer
        ) == VoiceEnginePolicy.ENGINE_ON_DEVICE
    }

    /** Creates a recognizer with the requested engine and starts listening. Main thread only. */
    private fun startEngine(onDevice: Boolean) {
        destroyCurrent()

        // The policy gate in [preferOnDevice] is opaque to lint's data-flow analysis (it runs
        // through chooseEngine), so NewApi needs a direct SDK_INT guard at the call site.
        // VERSION_CODES.S == 31 == VoiceEnginePolicy.ON_DEVICE_MIN_SDK.
        val sr = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (t: Throwable) {
                Log.w(TAG, "On-device recognizer unavailable, falling back", t)
                null
            }
        } else {
            null
        }
        usesOnDeviceEngine = sr != null
        val engine = sr ?: SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine
        lastPartial = null
        attemptStartedAtMs = System.currentTimeMillis()

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                // No finals arrived; promote the partial so a long dictation is
                // not silently dropped, but only if the engine seemed confident
                // enough to transcribe something.
                val promoted = VoiceEnginePolicy.pickFinalText(null, lastPartial)
                if (promoted.isNotEmpty()) {
                    onFinalText(promoted)
                    return
                }
                // A recognizer that fails immediately with nothing transcribed (client,
                // busy, permission-class errors — the on-device recognizer does this on
                // several ROMs) gets exactly ONE retry on the other engine. Without it the
                // "Listening…" chip flashed and vanished seconds after every tap, which
                // reads as a crash.
                if (retryCount < 1 && System.currentTimeMillis() - attemptStartedAtMs < 2500) {
                    val canTryOther = if (usesOnDeviceEngine) true
                        else Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                    if (canTryOther) {
                        retryCount++
                        Log.w(TAG, "Recognizer failed fast (error $error); retrying with " +
                            (if (usesOnDeviceEngine) "the standard engine" else "the on-device engine"))
                        startEngine(!usesOnDeviceEngine)
                        return
                    }
                }
                onPartialText("")
                // Surface the reason instead of silently dropping the chip.
                onVoiceError(VoiceEnginePolicy.describeError(error))
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val finals = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = VoiceEnginePolicy.pickFinalText(finals, lastPartial)
                onPartialText("")
                if (text.isNotEmpty()) onFinalText(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                lastPartial = partial
                onPartialText(VoiceEnginePolicy.hudText(partial))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            val lang = sessionLanguage
            if (!lang.isNullOrEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            }
        }
        isListening = true
        try {
            engine.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to start voice input", t)
            cancel()
            onPartialText("")
            onVoiceError(VoiceEnginePolicy.describeError(5)) // ERROR_CLIENT
        }
    }

    fun cancel() {
        destroyCurrent()
        isListening = false
        lastPartial = null
        usesOnDeviceEngine = false
    }

    /** Stops and releases the current recognizer, if any. Safe to call any time. */
    private fun destroyCurrent() {
        val sr = recognizer
        recognizer = null
        try { sr?.setRecognitionListener(null) } catch (e: Exception) { Log.w(TAG, "Failed to clear recognition listener", e) }
        try { sr?.cancel() } catch (e: Exception) { Log.w(TAG, "Failed to cancel speech recognizer", e) }
        try { sr?.destroy() } catch (e: Exception) { Log.w(TAG, "Failed to destroy speech recognizer", e) }
    }

    companion object {
        private const val TAG = "VoiceInputController"

        /** True = prefer the offline recognizer when the device ships one. */
        const val PREF_ON_DEVICE = "voice_on_device"
    }
}
