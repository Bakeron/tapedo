package io.github.bakeron.tapedo

import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bakeron.tapedo.locale.AppLanguage
import io.github.bakeron.tapedo.ui.TapedoNavHost
import io.github.bakeron.tapedo.ui.theme.NightMode
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import io.github.bakeron.tapedo.ui.theme.isDark

// AppCompatActivity (not plain ComponentActivity) so the per-app language
// switch works on Android versions older than 13.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.applyDefaultIfUnset()
        enableEdgeToEdge()

        val container = (application as TapedoApp).container
        // Emulator testing without speech: debuggable builds accept a ready transcript. Release builds ignore it.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val debugTranscript = if (debuggable) intent.getStringExtra("debug_transcript") else null

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = container.lastSettings)
            val systemAnimationsOff = systemAnimationsOff()
            // On a cold start, until settings load (a few ms), only the window background shows - no theme flash.
            val s = settings ?: return@setContent
            SideEffect { container.lastSettings = s }
            // The window background and splash follow the theme chosen in Tapedo from the next start on.
            LaunchedEffect(s.theme) { NightMode.apply(applicationContext, s.theme) }
            // Status / navigation bar icons follow the theme chosen in Tapedo, not the system one.
            val dark = s.theme.isDark()
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            val still = s.reduceMotion || systemAnimationsOff
            TapedoTheme(mode = s.theme, reduceMotion = still, tilted = s.tiltedPaper, textScale = s.textScale) {
                val dip = languageDip(still)
                Box(Modifier.graphicsLayer { alpha = dip() }) {
                    TapedoNavHost(container, debugTranscript)
                }
            }
        }
    }
}

/**
 * A new app language arrives as a configuration change (the screen is not rebuilt - see the manifest):
 * the texts switch in place while the page dims for a moment and comes back, like a typewriter's page
 * being retyped - instead of a jump. Gives the page's alpha; always 1 with reduced motion.
 */
@Composable
private fun languageDip(still: Boolean): () -> Float {
    val locale = LocalConfiguration.current.locales[0]
    var settled by remember { mutableStateOf(locale) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(locale) {
        if (locale == settled) return@LaunchedEffect
        fade.snapTo(DIP)
        settled = locale
        if (!still) fade.animateTo(1f, tween(360))
        else fade.snapTo(1f)
    }
    // Dimmed already in the frame the new texts appear, not one frame later. Read while drawing,
    // so the fade does not recompose the whole app on every frame.
    return {
        when {
            still -> 1f
            locale != settled -> DIP
            else -> fade.value
        }
    }
}

private const val DIP = 0.3f

/**
 * "Remove animations" in Android accessibility settings (animator scale 0) - watched live,
 * so switching it while Tapedo is open takes effect at once, not after a restart.
 */
@Composable
private fun systemAnimationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var off by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                off = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        // The setting may have changed while the observer was not yet registered.
        off = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return off
}
