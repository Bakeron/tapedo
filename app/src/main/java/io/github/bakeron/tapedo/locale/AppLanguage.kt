package io.github.bakeron.tapedo.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Per-app language (Polish / English), independent of the phone's language.
 * Stored by the system on Android 13+ and by AppCompat on older versions.
 */
object AppLanguage {
    const val POLISH = "pl"
    const val ENGLISH = "en"
    val ALL = listOf(POLISH, ENGLISH)

    fun current(): String {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return if (tags.startsWith(ENGLISH)) ENGLISH else POLISH
    }

    /** Polish by default (docs/zalozenia.md), even on a phone set to another language. */
    fun applyDefaultIfUnset() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) set(POLISH)
    }

    /** Recreates the activity in the new language. */
    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
