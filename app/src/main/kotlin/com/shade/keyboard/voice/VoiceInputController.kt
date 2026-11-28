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
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Voice input session manager.
 *
 * The speech stack is a foreign, OEM-specific service: it throws from create /
 * startListening / cancel / destroy, delivers callbacks after destroy, and dies
 * mid-session. An IME must survive all of it, so:
 *  - every SpeechRecognizer call happens on the main thread inside runCatching;
 *  - each attempt gets a FRESH recognizer (a used one throws on startListening);
 *  - a retry is POSTED, never run inside the recognizer's own error callback
 *    (destroy+create+start in one frame is what produces "Service not registered");
 *  - a session token drops late callbacks from an already-torn-down recognizer;
 *  - [release] tears down immediately for Service.onDestroy, and closes the
 *    controller so nothing touches the recognizer afterwards;
 *  - the app context is used, so no destroyed Service is held;
 *  - callbacks into the IME are guarded (a bad string resource or a dead
 *    InputConnection must not take the keyboard process down).
 *
 * Engine selection and recovery (the parts that make voice WORK on real devices):
 *  - the "voice_on_device" settings toggle is honoured ([VoiceEnginePolicy.chooseEngine]);
 *  - when one engine fails at runtime, the other one is tried before giving up:
 *    create/start failures, language errors (offline model missing → standard
 *    engine, which can use the network) and one transient retry per engine;
 *  - a partial transcript survives an engine failure ([VoiceEnginePolicy.pickFinalText])
 *    so a long dictation is not silently dropped.
 */
class VoiceInputController(
    context: Context,
    private val prefs: SharedPreferences,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onVoiceError: (String) -> Unit
) {
    private val appContext: Context = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var attempts = 0
    private var preferOnDevice = false
    private var usedOnDevice = false
    private var lastPartial: String? = null
    @Volatile private var closed = false

    fun start() {
        main.post {
            if (closed) return@post
            if (!hasMic()) {
                launchPermissionScreen()
                emit { onVoiceError("Microphone access is off — allow it, then tap the mic again") }
                return@post
            }
            val onDevice = onDeviceAvailable()
            if (!onDevice && !recognitionAvailable()) {
                emit { onVoiceError("No speech recognizer is available on this device") }
                return@post
            }
            // The settings toggle ("voice_on_device") must keep working: without
            // it there is no escape from a device whose on-device engine is
            // broken (stub reports availability but no offline model exists).
            preferOnDevice = VoiceEnginePolicy.chooseEngine(
                Build.VERSION.SDK_INT, onDevice,
                prefs.getBoolean(PREF_ON_DEVICE, true)
            ) == VoiceEnginePolicy.ENGINE_ON_DEVICE
            attempts = 0
            beginSession()
        }
    }

    /** Stop the current session; the controller stays usable. */
    fun cancel() {
        main.post {
            session++      // invalidate anything still in flight
            teardown()
        }
    }

    /**
     * Final, immediate teardown — call from Service.onDestroy (main thread).
     * After this the controller never touches the recognizer again, so no
     * SpeechRecognizer work can run against a destroyed Service context.
     */
    fun release() {
        closed = true
        session++
        main.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == main.looper) teardown() else main.post { teardown() }
    }

    // ------------------------------------------------------------------ session

    private fun beginSession() {
        if (closed) return
        teardown()
        val token = ++session
        lastPartial = null
        val r = createRecognizer()
        if (r == null) {
            emit { onVoiceError("Speech service could not start — tap the mic to retry") }
            return
        }
        recognizer = r
        val started = runCatching {
            r.setRecognitionListener(listenerFor(token))
            r.startListening(buildIntent())
        }.isSuccess
        if (!started) {
            teardown()
            // The engine we tried could not even start — switch to the other one
            // before giving up (on-device stubs often bind but never serve).
            if (usedOnDevice && attempts < VoiceEnginePolicy.MAX_ENGINE_ATTEMPTS &&
                recognitionAvailable()
            ) {
                attempts++
                preferOnDevice = false
                usedOnDevice = false
                main.post { if (live(token)) beginSession() }
                return
            }
            emit { onVoiceError("Speech service could not start — tap the mic to retry") }
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        usedOnDevice = false
        if (preferOnDevice && Build.VERSION.SDK_INT >= 31) {
            val onDevice = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }.getOrNull()
            if (onDevice != null) {
                usedOnDevice = true
                return onDevice
            }
            // Create failed: fall through to the standard recognizer so the
            // session still has a chance (create can throw even when
            // isOnDeviceRecognitionAvailable() said yes).
        }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
    }

    private fun teardown() {
        val r = recognizer ?: return
        recognizer = null
        // Any of these can throw on OEM builds (unbindService →
        // IllegalArgumentException "Service not registered" once the recognition
        // service has died). Drop the listener first so no late callback lands.
        runCatching { r.setRecognitionListener(null) }
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
    }

    private fun language(): String {
        val configured = runCatching { prefs.getString("voice_language", null) }.getOrNull()
        if (!configured.isNullOrBlank()) return configured.trim()
        return runCatching { Locale.getDefault().toLanguageTag() }.getOrDefault("en-US")
    }

    // ------------------------------------------------------------------ callbacks

    private fun listenerFor(token: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live(token)) return
            val t = first(partialResults)
            if (t.isNotEmpty()) {
                lastPartial = t
                emit { onPartialText(VoiceEnginePolicy.hudText(t)) }
            }
        }

        override fun onResults(results: Bundle?) {
            if (!live(token)) return
            val t = VoiceEnginePolicy.pickFinalText(
                first(results).takeIf { it.isNotEmpty() }?.let { listOf(it) },
                lastPartial
            )
            teardown()
            emit {
                if (t.isNotEmpty()) onFinalText(t)
                else onVoiceError("Didn't catch that — tap the mic and speak again")
            }
        }

        override fun onError(error: Int) {
            if (!live(token)) return
            // The engine transcribed something before failing: a long dictation
            // must not be silently dropped — deliver the partial as the result.
            val promoted = VoiceEnginePolicy.pickFinalText(null, lastPartial)
            teardown()
            if (promoted.isNotEmpty()) {
                emit { onFinalText(promoted) }
                return
            }
            // Recovery is policy, not vibes: language errors leave the on-device
            // engine for the network-capable one, transient errors retry, and a
            // dead on-device engine hands over to standard before reporting.
            when (VoiceEnginePolicy.recoveryAction(
                usedOnDevice, error, attempts,
                standardAvailable = recognitionAvailable(),
                onDeviceAvailable = onDeviceAvailable(),
                preferOnDeviceWanted = prefs.getBoolean(PREF_ON_DEVICE, true)
            )) {
                VoiceEnginePolicy.ErrorAction.RETRY ->
                    main.post { if (live(token)) beginSession() }
                VoiceEnginePolicy.ErrorAction.SWITCH_TO_STANDARD -> {
                    attempts++
                    preferOnDevice = false
                    main.post { if (live(token)) beginSession() }
                }
                VoiceEnginePolicy.ErrorAction.SWITCH_TO_ON_DEVICE -> {
                    attempts++
                    preferOnDevice = true
                    main.post { if (live(token)) beginSession() }
                }
                VoiceEnginePolicy.ErrorAction.REPORT ->
                    emit { onVoiceError(message(error)) }
            }
        }
    }

    private fun live(token: Int): Boolean = !closed && token == session

    /** Callbacks run on the next main-loop turn, guarded: the IME must not die here. */
    private fun emit(block: () -> Unit) {
        main.post {
            if (closed) return@post
            runCatching(block).onFailure { Log.w(TAG, "voice callback failed", it) }
        }
    }

    private fun first(b: Bundle?): String = runCatching {
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
    }.getOrDefault("")

    private fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO ->
            "Microphone is busy — close apps using it and retry"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone access is off — allow it, then tap again"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "Didn't catch that — tap the mic and speak again"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech needs a network connection here — connect and retry"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Offline speech isn't installed for this language — enable it in device settings"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "This device's speech service doesn't support this language"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "Speech service is overloaded — try again in a minute"
        else -> "Speech service hiccup — tap the mic to retry"
    }

    // ------------------------------------------------------------------ platform probes

    private fun hasMic(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        }.getOrDefault(false)

    private fun recognitionAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)

    private fun launchPermissionScreen() {
        runCatching {
            appContext.startActivity(
                Intent(appContext, VoicePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "could not open the mic permission screen", it) }
    }

    private companion object {
        const val TAG = "VoiceInput"

        /** Settings toggle (SettingsCatalog) — restored: without it a device
         *  whose on-device engine is broken has no escape hatch. */
        const val PREF_ON_DEVICE = "voice_on_device"
    }
}
