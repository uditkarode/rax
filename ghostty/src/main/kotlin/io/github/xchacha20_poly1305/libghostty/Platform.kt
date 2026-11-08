package io.github.xchacha20_poly1305.libghostty

import android.util.Log

/** Loads the JNI library that backs [GhosttyVt]. */
internal fun loadGhosttyJni() {
    System.loadLibrary("ghostty_jni")
}

internal fun logError(tag: String, message: String, error: Throwable? = null) {
    Log.e(tag, message, error)
}
