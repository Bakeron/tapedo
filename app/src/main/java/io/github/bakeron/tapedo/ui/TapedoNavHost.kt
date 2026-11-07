package io.github.bakeron.tapedo.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
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
        // The screen underneath only dims a little while a card covers it.
        exitTransition = { if (still) ExitTransition.None else fadeOut(targetAlpha = 0.6f) },
        popEnterTransition = { if (still) EnterTransition.None else fadeIn(initialAlpha = 0.6f) },
        popExitTransition = { if (still) ExitTransition.None else fadeOut() },
    ) {
        composable<HomeDestination> {
            val vm = viewModel {
                HomeViewModel(container.settings, container.speech, container.notes, container.structurer, container.lockedNotice)
            }
            HomeRoute(vm, onOpenSettings = { nav.navigate(SettingsDestination) }, debugTranscript = debugTranscript)
        }
        // Settings and licences slide up from below, like a card pulled out from under the recorder,
        // and slide back down; the recorder stays put behind them.
        composable<SettingsDestination>(
            enterTransition = { cardIn(still) },
            popExitTransition = { cardOut(still) },
        ) {
            val vm = viewModel { SettingsViewModel(container.settings, container.secrets, container.aiUsage, container.aiConnection, container.speech) }
            SettingsRoute(vm, onBack = { nav.popBackStack() }, onOpenLicenses = { nav.navigate(LicensesDestination) })
        }
        composable<LicensesDestination>(
            enterTransition = { cardIn(still) },
            popExitTransition = { cardOut(still) },
        ) {
            LicensesScreen(onBack = { nav.popBackStack() })
        }
    }
}

private val cardSpring = spring<IntOffset>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

private fun cardIn(still: Boolean): EnterTransition =
    if (still) EnterTransition.None else slideInVertically(cardSpring) { it } + fadeIn()

private fun cardOut(still: Boolean): ExitTransition =
    if (still) ExitTransition.None else slideOutVertically(tween(220)) { it } + fadeOut(tween(220))
