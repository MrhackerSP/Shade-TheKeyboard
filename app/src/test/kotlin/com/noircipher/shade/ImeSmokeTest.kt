package com.noircipher.shade

import android.graphics.Canvas
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real IME lifecycle on the JVM: onCreate -> onCreateInputView -> onStartInputView ->
 * measure / layout / draw. Any exception that would kill the keyboard process when the user taps a
 * text field fails this test with the original stack trace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeSmokeTest {

    private fun editorInfo(inputType: Int): EditorInfo = EditorInfo().apply {
        this.inputType = inputType
        packageName = "com.example.test"
        fieldId = 1
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
    }

    @Test
    fun serviceLifecycle_fullPipeline_doesNotThrow() {
        val crashes = mutableListOf<Throwable>()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            crashes.add(e)
            original?.uncaughtException(t, e)
        }
        try {
            val controller = Robolectric.buildService(ShadeService::class.java)
            val service = controller.create().get()

            val view = service.onCreateInputView()
            Assert.assertNotNull("onCreateInputView returned null", view)

            // Every field class the IME can encounter.
            val types = intArrayOf(
                InputType.TYPE_CLASS_TEXT,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME,
                InputType.TYPE_NULL
            )
            val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)

            for (t in types) {
                service.onStartInputView(editorInfo(t), false)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, 1080, 600)
                val canvas = Canvas()
                view.draw(canvas)
            }

            // A second, restarting pass exercises the "restarting = true" path.
            service.onStartInputView(editorInfo(InputType.TYPE_CLASS_TEXT), true)
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, 1080, 600)
            view.draw(Canvas())

            controller.destroy()
            // Give background coroutines (configureLocaleAssets, dictionary load) a chance to run.
            for (i in 0 until 20) {
                SystemClock.sleep(50)
                Thread.sleep(50)
            }
            Assert.assertTrue(
                "Uncaught exceptions on background threads: ${crashes.joinToString { it.toString() }}",
                crashes.isEmpty()
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
