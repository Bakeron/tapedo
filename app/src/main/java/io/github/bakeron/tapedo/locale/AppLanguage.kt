package io.github.bakeron.tapedo.locale

import android.content.res.Resources
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

    /** The phone's own language ("pl", "de", ...), whatever Tapedo is set to. */
    fun phoneLanguage(): String = Resources.getSystem().configuration.locales[0]?.language.orEmpty()

    /**
     * After installing: Polish on a phone in Polish, English on any other (author 11.11.2026, as in
     * Mikropłatności). Whoever already has Tapedo keeps the language they have - it is stored.
     */
    fun applyDefaultIfUnset() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) set(if (phoneLanguage() == POLISH) POLISH else ENGLISH)
    }

    /** Recreates the activity in the new language. */
    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
