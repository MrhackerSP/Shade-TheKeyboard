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
    private val onFinalText: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var lastPartial: String? = null
    var isListening = false
        private set

    /** True when the active session uses the offline recognizer. */
    var usesOnDeviceEngine = false
        private set

    fun start(languageTag: String? = null) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, R.string.voice_input_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
        val preferOnDevice = prefs.getBoolean(PREF_ON_DEVICE, true)
        val onDeviceOk = Build.VERSION.SDK_INT >= VoiceEnginePolicy.ON_DEVICE_MIN_SDK &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val wantOnDevice = VoiceEnginePolicy.chooseEngine(
            Build.VERSION.SDK_INT, onDeviceOk, preferOnDevice
        ) == VoiceEnginePolicy.ENGINE_ON_DEVICE

        // The policy gate above is opaque to lint's data-flow analysis (it runs
        // through chooseEngine), so NewApi needs a direct SDK_INT guard at the
        // call site. VERSION_CODES.S == 31 == VoiceEnginePolicy.ON_DEVICE_MIN_SDK,
        // so this is behaviour-preserving: wantOnDevice already implies it.
        val sr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wantOnDevice) {
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
                if (promoted.isNotEmpty()) onFinalText(promoted) else onPartialText("")
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
            if (!languageTag.isNullOrEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            }
        }
        isListening = true
        try {
            engine.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to start voice input", t)
            cancel()
            onPartialText("")
        }
    }

    fun cancel() {
        val sr = recognizer
        recognizer = null
        isListening = false
        lastPartial = null
        usesOnDeviceEngine = false
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
