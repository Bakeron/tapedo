package io.github.bakeron.tapedo

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bakeron.tapedo.locale.AppLanguage
import io.github.bakeron.tapedo.ui.TapedoNavHost
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
        // "Remove animations" in Android accessibility settings sets this scale to 0.
        val systemAnimationsOff =
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        // Emulator testing without speech: debuggable builds accept a ready transcript. Release builds ignore it.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val debugTranscript = if (debuggable) intent.getStringExtra("debug_transcript") else null

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            // Until settings load (a few ms) only the window background shows - no theme flash.
            val s = settings ?: return@setContent
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
