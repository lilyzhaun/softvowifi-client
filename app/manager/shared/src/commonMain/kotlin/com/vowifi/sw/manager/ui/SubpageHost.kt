package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.AppRouteCodec

internal data class SubpageHostNavDisplayTransitionEffectsContract(
    val cornerClip: Boolean,
    val dimAmount: Float,
    val inputBlocking: Boolean,
    val isDefaultEffects: Boolean,
    val isNoneEffects: Boolean,
    val sharedBetweenPredictiveOnAndOff: Boolean,
)

internal val subpageHostNavDisplayTransitionEffects = NavDisplayTransitionEffects(
    enableCornerClip = false,
    dimAmount = 0f,
    blockInputDuringTransition = true,
)

internal fun subpageHostNavDisplayTransitionEffectsContract(): SubpageHostNavDisplayTransitionEffectsContract =
    SubpageHostNavDisplayTransitionEffectsContract(
        cornerClip = subpageHostNavDisplayTransitionEffects.enableCornerClip,
        dimAmount = subpageHostNavDisplayTransitionEffects.dimAmount,
        inputBlocking = subpageHostNavDisplayTransitionEffects.blockInputDuringTransition,
        isDefaultEffects = subpageHostNavDisplayTransitionEffects == NavDisplayTransitionEffects.Default,
        isNoneEffects = subpageHostNavDisplayTransitionEffects == NavDisplayTransitionEffects.None,
        sharedBetweenPredictiveOnAndOff = true,
    )

internal data class SubpageHostCompositionContract(
    val composedOnRoot: Boolean,
    val composedOnSubpage: Boolean,
    val rootTransitionPolicy: AppShellTransitionPolicy,
    val subpageTransitionPolicy: AppShellTransitionPolicy,
    val rootEntryCount: Int,
    val rootEntriesPassThrough: Boolean,
    val rootEntriesOpaque: Boolean,
    val navDisplayTransitionEffects: SubpageHostNavDisplayTransitionEffectsContract = subpageHostNavDisplayTransitionEffectsContract(),
    val predictivePopUsesDedicatedTransform: Boolean = true,
    val predictivePopSharesRegularPopTransform: Boolean = false,
    val motionSource: SubpageMotionSource = subpageMotionContract().source,
    val customTransitionOverrides: Boolean = subpageMotionContract().customTransitionOverrides,
)

internal fun subpageHostCompositionContract(): SubpageHostCompositionContract = SubpageHostCompositionContract(
    composedOnRoot = true,
    composedOnSubpage = true,
    rootTransitionPolicy = AppShellTransitionPolicy.None,
    subpageTransitionPolicy = AppShellTransitionPolicy.SlideOpaque,
    rootEntryCount = AppRoute.rootRoutes.size,
    rootEntriesPassThrough = true,
    rootEntriesOpaque = false,
    navDisplayTransitionEffects = subpageHostNavDisplayTransitionEffectsContract(),
    predictivePopUsesDedicatedTransform = true,
    predictivePopSharesRegularPopTransform = false,
    motionSource = subpageMotionContract().source,
    customTransitionOverrides = subpageMotionContract().customTransitionOverrides,
)

private const val ROOT_CONTENT_KEY_PREFIX = "root:"
private const val SUBPAGE_CONTENT_KEY_PREFIX = "subpage:"

internal fun appRouteContentKey(route: AppRoute): String = when (route) {
    is AppRoute.Root -> ROOT_CONTENT_KEY_PREFIX + AppRouteCodec.encode(route)
    is AppRoute.Subpage -> SUBPAGE_CONTENT_KEY_PREFIX + AppRouteCodec.encode(route)
}

internal fun subpageHostTransitionPolicy(
    initialSceneKey: Any,
    targetSceneKey: Any,
    pop: Boolean,
): AppShellTransitionPolicy {
    val animatedSceneKey = if (pop) initialSceneKey else targetSceneKey
    return if (animatedSceneKey is String && animatedSceneKey.startsWith(SUBPAGE_CONTENT_KEY_PREFIX)) {
        AppShellTransitionPolicy.SlideOpaque
    } else {
        AppShellTransitionPolicy.None
    }
}

@Composable
internal fun SubpageHost(
    state: AppNavigationState,
    topBarTitleOverride: String?,
    onStateChange: (AppNavigationState) -> Unit,
    destinationContent: @Composable (AppRoute) -> Unit,
) {
    val spec = LocalSoftVoWiFiChromeEnvironment.current.spec
    val latestState = rememberUpdatedState(state)
    val entries = subpageEntryProvider(state, spec, topBarTitleOverride, onStateChange, destinationContent,
        communicationActive = { isRouteActive(latestState.value, it) })
    val onBack = { onStateChange(AppNavigator.back(state)) }
    val decorated = rememberDecoratedNavEntries(
        // The pager owns root-to-root motion. Keep its empty NavDisplay underlay
        // stable so invisible root scenes cannot start an input-blocking transition.
        backStack = state.backStack.map { if (it is AppRoute.Root) AppRoute.Home else it },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider = entries,
    )
    val scenes = rememberSceneState(decorated, sceneStrategies = listOf(SinglePaneSceneStrategy()), onBack = onBack)
    val gesture = rememberNavigationEventState(scenes)
    val resting = rememberNavigationEventState(scenes)
    NavigationBackHandler(scenes, gesture, onBack)
    key(spec.predictiveBackEnabled) {
        NavDisplay(
            sceneState = scenes,
            navigationEventState = subpageBackRenderState(spec.predictiveBackEnabled, gesture, resting),
            transitionEffects = subpageHostNavDisplayTransitionEffects,
        )
    }
}

private fun subpageEntryProvider(
    state: AppNavigationState,
    spec: AppChromeSpec,
    topBarTitleOverride: String?,
    onStateChange: (AppNavigationState) -> Unit,
    destinationContent: @Composable (AppRoute) -> Unit,
    communicationActive: (AppRoute.Subpage) -> Boolean,
) = entryProvider {
    entry<AppRoute.Home>(clazzContentKey = ::appRouteContentKey) { Box(Modifier.fillMaxSize()) }
    entry<AppRoute.Calls>(clazzContentKey = ::appRouteContentKey) { Box(Modifier.fillMaxSize()) }
    entry<AppRoute.Messages>(clazzContentKey = ::appRouteContentKey) { Box(Modifier.fillMaxSize()) }
    entry<AppRoute.Settings>(clazzContentKey = ::appRouteContentKey) { Box(Modifier.fillMaxSize()) }
    entry<AppRoute.MessageThread>(clazzContentKey = ::appRouteContentKey) {
        AppSubpageScene(
            it,
            spec,
            titleOverride = subpageTitleOverride(it, topBarTitleOverride),
            routeActive = communicationActive(it),
            onBack = { onStateChange(AppNavigator.back(state)) },
        ) { destinationContent(it) }
    }
    entry<AppRoute.NewMessage>(clazzContentKey = ::appRouteContentKey) { AppSubpageScene(AppRoute.NewMessage, spec, routeActive = isRouteActive(state, AppRoute.NewMessage), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(AppRoute.NewMessage) } }
    entry<AppRoute.Dialpad>(clazzContentKey = ::appRouteContentKey) { AppSubpageScene(AppRoute.Dialpad, spec, routeActive = communicationActive(AppRoute.Dialpad), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(AppRoute.Dialpad) } }
    entry<AppRoute.CallHistoryDetail>(clazzContentKey = ::appRouteContentKey) {
        AppSubpageScene(it, spec, routeActive = isRouteActive(state, it), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(it) }
    }
    entry<AppRoute.SettingsAppearance>(clazzContentKey = ::appRouteContentKey) { AppSubpageScene(AppRoute.SettingsAppearance, spec, routeActive = isRouteActive(state, AppRoute.SettingsAppearance), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(AppRoute.SettingsAppearance) } }
    entry<AppRoute.SettingsConfiguration>(clazzContentKey = ::appRouteContentKey) { AppSubpageScene(AppRoute.SettingsConfiguration, spec, routeActive = isRouteActive(state, AppRoute.SettingsConfiguration), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(AppRoute.SettingsConfiguration) } }
    entry<AppRoute.SettingsConfigurationSim>(clazzContentKey = ::appRouteContentKey) {
        AppSubpageScene(it, spec, routeActive = isRouteActive(state, it), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(it) }
    }
    entry<AppRoute.SettingsDiagnostics>(clazzContentKey = ::appRouteContentKey) { AppSubpageScene(AppRoute.SettingsDiagnostics, spec, routeActive = isRouteActive(state, AppRoute.SettingsDiagnostics), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(AppRoute.SettingsDiagnostics) } }
    entry<AppRoute.SettingsLogs>(clazzContentKey = ::appRouteContentKey) { AppSubpageScene(AppRoute.SettingsLogs, spec, routeActive = isRouteActive(state, AppRoute.SettingsLogs), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(AppRoute.SettingsLogs) } }
    entry<AppRoute.SettingsCallNotifications>(clazzContentKey = ::appRouteContentKey) { AppSubpageScene(AppRoute.SettingsCallNotifications, spec, routeActive = isRouteActive(state, AppRoute.SettingsCallNotifications), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(AppRoute.SettingsCallNotifications) } }
    entry<AppRoute.SettingsAbout>(clazzContentKey = ::appRouteContentKey) { AppSubpageScene(AppRoute.SettingsAbout, spec, routeActive = isRouteActive(state, AppRoute.SettingsAbout), onBack = { onStateChange(AppNavigator.back(state)) }) { destinationContent(AppRoute.SettingsAbout) } }
}
