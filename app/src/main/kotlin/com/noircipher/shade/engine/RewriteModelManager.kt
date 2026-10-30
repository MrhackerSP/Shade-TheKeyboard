package com.noircipher.shade.engine

import android.content.Context

class RewriteModelManager(context: Context) {
    val engine: RewriteEngine = TfliteRewriteEngine(context)

    val available: Boolean
        get() = engine.isAvailable

    fun close() {
        engine.close()
    }
}
