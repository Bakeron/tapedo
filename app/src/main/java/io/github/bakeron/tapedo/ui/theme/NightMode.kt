package io.github.bakeron.tapedo.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import io.github.bakeron.tapedo.core.settings.ThemeMode

/**
 * Tells the window (background, splash screen) which theme Tapedo uses, so the first frames
 * match the app's choice instead of the system's - no flash of the other theme on start.
 *
 * The choice is mirrored in a tiny preferences file because the window needs it synchronously,
 * before the settings (DataStore, asynchronous) have loaded.
 */
object NightMode {
    private const val FILE = "night_mode"
    private const val KEY = "mode"

    /** Call in Application.onCreate, before any activity starts. */
    fun restore(context: Context) {
        val mode = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * Applies a theme chosen in Settings. The manifest handles uiMode itself, so the screen is not rebuilt:
     * the switches slide to the new choice instead of jumping (author 11.11.2026).
     */
    fun apply(context: Context, theme: ThemeMode) {
        val mode = when (theme) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() == mode) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
