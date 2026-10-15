package com.noircipher.shade

/**
 * Per-app keyboard profile. Applied in onStartInputView based on the
 * foreground package name.
 */
data class KeyboardProfile(
    val packageName: String,
    val suggestions: Boolean = true,
    val autocorrect: Boolean = true,
    val learning: Boolean = true,
    val incognito: Boolean = false
)

object ProfileStore {
    val defaults: List<KeyboardProfile> = listOf(
        // Banking / password managers: no suggestions, no learning, no clipboard
        KeyboardProfile("com.android.settings", suggestions = false, autocorrect = false, learning = false),
        KeyboardProfile("com.google.android.apps.authenticator2", suggestions = false, autocorrect = false, learning = false, incognito = true),
        KeyboardProfile("com.bitwarden", suggestions = false, autocorrect = false, learning = false, incognito = true),
        KeyboardProfile("com.lastpass", suggestions = false, autocorrect = false, learning = false, incognito = true),
        KeyboardProfile("com.onepassword", suggestions = false, autocorrect = false, learning = false, incognito = true)
    )

    fun forPackage(pkg: String?, userProfiles: List<KeyboardProfile>): KeyboardProfile? {
        if (pkg == null) return null
        return userProfiles.firstOrNull { it.packageName == pkg }
    }
}
