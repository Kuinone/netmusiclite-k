package com.netmusiclite.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.CrashLogger
import com.netmusiclite.data.M3eBackgroundStyle
import com.netmusiclite.ui.m3e.m3eSwipeBackFallback
import androidx.wear.compose.foundation.LocalSwipeToDismissBackgroundScrimColor
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable as classicComposable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.WearNavigator
import androidx.wear.compose.navigation.composable as wearComposable

/** Both navigators are registered so saved route IDs and arguments survive a theme change. */
@Composable
internal fun rememberThemeNavController(material: Boolean): NavHostController {
    val previous = remember { arrayOfNulls<NavHostController>(1) }
    return key(material) {
        val snapshot = remember { previous[0]?.saveState() }
        val wearNavigator = remember { WearNavigator() }
        val controller = rememberNavController(wearNavigator)
        remember(controller) {
            snapshot?.let(controller::restoreState)
            previous[0] = controller
        }
        controller
    }
}

/** Native Wear owns entry, swipe preview/cancellation, dismissal and platform back together. */
@Composable
internal fun ThemeNavHost(
    navController: NavHostController,
    startDestination: String,
    material: Boolean,
    modifier: Modifier = Modifier,
    enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
    popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
    builder: NavGraphBuilder.() -> Unit,
) {
    var initialStackCreated by rememberSaveable { mutableStateOf(false) }
    if (material) {
        val entry by navController.currentBackStackEntryAsState()
        remember(entry?.id) {
            CrashLogger.step("m3e host dest=${entry?.destination?.route} bg=${AppearancePrefs.m3eBackgroundStyle}")
        }
        // The cold-start player must have a real home beneath it for swipe-to-dismiss to reveal.
        val start = if (startDestination == Routes.PLAYER) Routes.HOME else startDestination
        // BasicSwipeToDismissBox paints this color as an opaque foreground background even
        // at rest. Keep it transparent so each destination can paint its own background.
        // ContentScrimColor stays unchanged to retain native gesture dimming.
        CompositionLocalProvider(LocalSwipeToDismissBackgroundScrimColor provides Color.Transparent) {
        RetainedExitFade(entry?.id, navController.previousBackStackEntry?.id,
            AppearancePrefs.m3eBackgroundStyle != M3eBackgroundStyle.BLACK) {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = start,
            // 三星类机型：Initial-pass 被动观测右滑，原生 swipe 没生效时补一次 pop（其它机型 no-op）
            modifier = modifier.fillMaxSize().m3eSwipeBackFallback(navController),
            userSwipeEnabled = entry?.destination?.route != Routes.HOME,
            builder = builder,
        )
        }
        }
        LaunchedEffect(navController) {
            if (!initialStackCreated && startDestination == Routes.PLAYER &&
                navController.currentDestination?.route == Routes.HOME &&
                navController.previousBackStackEntry == null
            ) {
                navController.navigateWithMotion(Routes.PLAYER)
            }
            initialStackCreated = true
        }
        LaunchedEffect(entry?.id) {
            // Login, onboarding and restored classic stacks can also clear directly to Player.
            // Keep the same home-under-player invariant on those paths, not only cold start.
            if (entry?.destination?.route == Routes.PLAYER &&
                navController.previousBackStackEntry == null
            ) {
                navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                navController.navigateWithMotion(Routes.PLAYER)
            }
        }
    } else {
        LaunchedEffect(navController) { initialStackCreated = true }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = modifier,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition,
            builder = builder,
        )
    }
}

private class ExitFrameBuffer {
    var key: String? = null
    var index: Int = 0
    var initialized = false
    var enabled = false
}

/** Wear's navigator removes the outgoing destination immediately on forward navigation.
 * Keep its last render layer alive until the fade completes, without retaining a second screen
 * composition. A pop is committed AFTER the native dismissal animation: replaying a cached
 * frame at that point resurrects the dismissed screen. Only a direct push may retain it. */
@Composable
private fun RetainedExitFade(routeKey: String?, previousEntryId: String?, enabled: Boolean, content: @Composable () -> Unit) {
    remember(routeKey) {
        CrashLogger.step("m3e fade route=$routeKey prev=$previousEntryId enabled=$enabled")
    }
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val buffers = remember { ExitFrameBuffer() }
    val transition = remember(routeKey) {
        val hasPrevious = buffers.initialized && buffers.key != routeKey
        val isForwardPush = hasPrevious && buffers.key != null && previousEntryId == buffers.key
        val fadePrevious = isForwardPush && (enabled || buffers.enabled)
        if (hasPrevious) buffers.index = 1 - buffers.index
        buffers.key = routeKey
        buffers.initialized = true
        buffers.enabled = enabled
        buffers.index to fadePrevious
    }
    val fade = remember(routeKey) { Animatable(if (transition.second) 1f else 0f) }
    LaunchedEffect(routeKey) {
        if (transition.second) {
            fade.snapTo(1f)
            fade.animateTo(0f, tween(420))
        } else fade.snapTo(0f)
    }
    val current = if (transition.first == 0) first else second
    val previous = if (transition.first == 0) second else first
    Box(Modifier.fillMaxSize().drawWithContent {
        current.alpha = 1f
        current.record { this@drawWithContent.drawContent() }
        drawLayer(current)
        if (transition.second && fade.value > 0f) {
            previous.alpha = fade.value
            drawLayer(previous)
        }
    }) { content() }
}

/** One route graph, rendered by the navigator that owns this theme's transitions. */
internal fun NavGraphBuilder.themeComposable(
    material: Boolean,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    backgroundInContent: Boolean = false,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    if (material) {
        wearComposable(route, arguments) { entry ->
            DestinationSurface(route, backgroundInContent, material = true) { content(entry) }
        }
    } else {
        classicComposable(route, arguments) { entry ->
            // 默认主题：页面自身不再画背景（由 AppRoot 的全局背景层承担，与 1.24-r2 一致）。
            DestinationSurface(route, backgroundInContent, material = false) { content(entry) }
        }
    }
}

/** Background and controls share the navigator's translation, scale and opacity (m3e only). */
@Composable
private fun DestinationSurface(
    route: String,
    backgroundInContent: Boolean,
    material: Boolean,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 默认主题用全局背景层（1.24-r2 转场架构），只有 m3e 才让背景跟着页面一起转场。
        if (material && !backgroundInContent) {
            com.netmusiclite.ui.components.NcmBackground(route = route, prepareResources = false)
        }
        content()
    }
}
