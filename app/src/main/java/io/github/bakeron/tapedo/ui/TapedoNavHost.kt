package io.github.bakeron.tapedo.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.bakeron.tapedo.AppContainer
import io.github.bakeron.tapedo.ui.home.HomeRoute
import io.github.bakeron.tapedo.ui.home.HomeViewModel
import io.github.bakeron.tapedo.ui.settings.LicensesScreen
import io.github.bakeron.tapedo.ui.settings.SettingsRoute
import io.github.bakeron.tapedo.ui.settings.SettingsViewModel
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlinx.serialization.Serializable

// Type-safe routes: the compiler checks every destination and its arguments.
@Serializable object HomeDestination
@Serializable object SettingsDestination
@Serializable object LicensesDestination

/** @param debugTranscript debug builds only - see [HomeRoute]. */
@Composable
fun TapedoNavHost(container: AppContainer, debugTranscript: String? = null) {
    val nav = rememberNavController()
    // "Reduce motion": screens switch at once instead of fading.
    val still = TapedoTheme.reduceMotion
    NavHost(
        navController = nav,
        startDestination = HomeDestination,
        enterTransition = { if (still) EnterTransition.None else fadeIn() },
        exitTransition = { if (still) ExitTransition.None else fadeOut() },
        popEnterTransition = { if (still) EnterTransition.None else fadeIn() },
        popExitTransition = { if (still) ExitTransition.None else fadeOut() },
    ) {
        composable<HomeDestination> {
            val vm = viewModel {
                HomeViewModel(container.settings, container.speech, container.notes, container.structurer, container.lockedNotice)
            }
            HomeRoute(vm, onOpenSettings = { nav.navigate(SettingsDestination) }, debugTranscript = debugTranscript)
        }
        composable<SettingsDestination> {
            val vm = viewModel { SettingsViewModel(container.settings, container.secrets, container.structurer) }
            SettingsRoute(vm, onBack = { nav.popBackStack() }, onOpenLicenses = { nav.navigate(LicensesDestination) })
        }
        composable<LicensesDestination> {
            LicensesScreen(onBack = { nav.popBackStack() })
        }
    }
}
