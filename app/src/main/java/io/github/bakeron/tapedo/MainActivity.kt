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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val systemAnimationsOff = systemAnimationsOff()
            // Until settings load (a few ms) only the window background shows - no theme flash.
            val s = settings ?: return@setContent
            // The window background and splash follow the theme chosen in Tapedo from the next start on.
            LaunchedEffect(s.theme) { NightMode.apply(applicationContext, s.theme) }
            // Status / navigation bar icons follow the theme chosen in Tapedo, not the system one.
            val dark = s.theme.isDark()
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            TapedoTheme(mode = s.theme, reduceMotion = s.reduceMotion || systemAnimationsOff, textScale = s.textScale) {
                TapedoNavHost(container, debugTranscript)
            }
        }
    }
}

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
