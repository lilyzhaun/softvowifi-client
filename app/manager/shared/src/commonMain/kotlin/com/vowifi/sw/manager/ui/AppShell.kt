package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.NavigationEventInfo
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.destination
import com.vowifi.sw.manager.ui.navigation.rootRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop

@Composable
fun AppShell(
    navigationState: AppNavigationState,
    onNavigationStateChange: (AppNavigationState) -> Unit,
    topBarTitleOverride: String? = null,
    appearanceSettings: AppearanceSettings,
    blurSupported: Boolean,
    rootSnapRequestId: Long = 0,
    messageUnreadSummary: com.vowifi.sw.manager.model.MessageUnreadSummary = com.vowifi.sw.manager.model.MessageUnreadSummary.Loading,
    ongoingCall: (@Composable () -> Unit)? = null,
    destinationContent: @Composable (AppRoute) -> Unit,
) {
    val colors = LocalSoftVoWiFiColors.current
    val policy = AppShellPolicy.forState(navigationState)
    val chromeSpec = appChromeSpec(appearanceSettings, blurSupported)
    val backdrop = rememberSoftVoWiFiBackdrop(chromeSpec)
    val rootPager = rememberAppRootPagerState(navigationState.currentRoot)
    val threadDrafts = rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(navigationState.currentRoot, rootSnapRequestId) {
        val requestedPage = pagerPageForRoot(navigationState.currentRoot)
        val forceSnap = rootSnapRequestId > 0
        if (shouldSynchronizeExternalRoot(rootPager.selectedPage, requestedPage, forceSnap)) {
            rootPager.selectRoot(
                navigationState.currentRoot,
                if (forceSnap) RootSelectionMode.SNAP else RootSelectionMode.ANIMATE,
            )
        }
    }
    LaunchedEffect(rootPager.pagerState, navigationState.topRoute) {
        snapshotFlow {
            Triple(
                rootPager.pagerState.targetPage,
                rootPager.pagerState.settledPage,
                rootPager.pagerState.isScrollInProgress,
            )
        }.distinctUntilChanged().collect { (targetPage, settledPage, scrollInProgress) ->
            rootPager.observePager(targetPage, settledPage, scrollInProgress)
            if (navigationState.topRoute is AppRoute.Root) {
                val root = rootForPagerPage(rootPager.selectedPage)
                if (root != navigationState.currentRoot) {
                    onNavigationStateChange(AppNavigator.selectRoot(navigationState, root))
                }
            }
        }
    }
    val rootBackState = rememberNavigationEventState(RootBackInfo)
    NavigationBackHandler(
        state = rootBackState,
        isBackEnabled = navigationState.topRoute is AppRoute.Root && navigationState.currentRoot != AppRoute.Home,
        onBackCompleted = {
            val home = AppNavigator.back(navigationState)
            rootPager.selectRoot(AppRoute.Home, RootSelectionMode.ANIMATE)
            onNavigationStateChange(home)
        },
    )

    CompositionLocalProvider(
        LocalMessageUnreadSummary provides messageUnreadSummary,
        LocalThreadDrafts provides threadDrafts,
        LocalSoftVoWiFiChromeEnvironment provides SoftVoWiFiChromeEnvironment(chromeSpec, backdrop),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {},
            bottomBar = {
                Box(Modifier.fillMaxWidth()) {
                    AnimatedBottomNavigationBar(
                        visible = policy.showBottomBar,
                        destination = rootForPagerPage(rootPager.selectedPage).destination,
                        onDestinationSelected = { selected ->
                            val root = selected.rootRoute
                            rootPager.selectRoot(root, RootSelectionMode.ANIMATE)
                            onNavigationStateChange(AppNavigator.selectRoot(navigationState, root))
                        },
                        chromeSpec = chromeSpec,
                        backdrop = backdrop,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            },
            containerColor = colors.canvas.copy(alpha = 1f),
        ) { padding ->
            val layoutInsets = rootBottomLayoutInsets(
                mode = chromeSpec.bottomMode,
                scaffoldBottomInset = padding.calculateBottomPadding(),
                navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            )
            Box(Modifier.fillMaxSize().background(colors.canvas.copy(alpha = 1f))) {
                RootPagerHost(
                    rootPager,
                    navigationState,
                    backdrop,
                    layoutInsets,
                    destinationContent,
                    if (ongoingCall != null) 80.dp else 0.dp,
                )
                SubpageHost(
                    navigationState,
                    topBarTitleOverride,
                    onNavigationStateChange,
                    destinationContent,
                )
                if (policy.showBottomBar && ongoingCall != null) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = layoutInsets.pageContentBottomInset)) { ongoingCall() }
                }
            }
        }
    }
}

@Composable
private fun RootPagerHost(
    state: AppRootPagerState,
    navigationState: AppNavigationState,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
    layoutInsets: RootBottomLayoutInsets,
    destinationContent: @Composable (AppRoute) -> Unit,
    ongoingInset: Dp,
) {
    val contract = appRootPagerContract()
    val capture = backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier
    Box(Modifier.fillMaxSize().then(capture)) {
        HorizontalPager(
            state = state.pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = contract.beyondViewportPageCount,
            userScrollEnabled = rootPagerSwipeEnabled(navigationState),
            key = { page -> pagerSaveableKey(page) },
        ) { page ->
            val route = rootForPagerPage(page)
            CompositionLocalProvider(
                LocalRootBottomContentInset provides RootBottomContentInset(layoutInsets.pageContentBottomInset + ongoingInset),
                LocalAppRouteActive provides isRouteActive(navigationState, route),
            ) {
                AppShellEntryHost { destinationContent(route) }
            }
        }
    }
}

data class AppShellPolicy(
    val showBottomBar: Boolean,
    val showTopBar: Boolean,
    val showBack: Boolean,
    val contentHost: AppShellContentHost,
    val transition: AppShellTransitionPolicy,
    val entrySurface: AppShellEntrySurfacePolicy,
) {
    companion object {
        fun forState(state: AppNavigationState): AppShellPolicy = if (state.topRoute is AppRoute.Root) {
            AppShellPolicy(true, false, false, AppShellContentHost.RootPager, AppShellTransitionPolicy.None, AppShellEntrySurfacePolicy.OpaqueCanvas)
        } else {
            AppShellPolicy(false, true, true, AppShellContentHost.NavDisplay, AppShellTransitionPolicy.SlideOpaque, AppShellEntrySurfacePolicy.OpaqueCanvas)
        }
    }
}

enum class AppShellContentHost { RootPager, NavDisplay }
enum class AppShellTransitionPolicy { None, SlideOpaque }
enum class AppShellEntrySurfacePolicy { OpaqueCanvas }

@Composable
private fun AppShellEntryHost(content: @Composable () -> Unit) {
    val colors = LocalSoftVoWiFiColors.current
    Box(Modifier.fillMaxSize().background(colors.canvas.copy(alpha = 1f))) { content() }
}

internal fun directRootKey(state: AppNavigationState): AppRoute.Root? = state.backStack.singleOrNull() as? AppRoute.Root

internal val AppRoute.topBarTitle: String
    get() = when (this) {
        AppRoute.Home -> "SoftVoWiFi"
        AppRoute.Calls -> "通话"
        AppRoute.Messages -> "短信"
        AppRoute.Settings -> "设置"
        is AppRoute.MessageThread -> "短信会话"
        AppRoute.NewMessage -> "新建短信"
        AppRoute.Dialpad -> "拨号"
        AppRoute.SettingsAppearance -> "外观"
        AppRoute.SettingsConfiguration -> "SIM 与网络配置"
        AppRoute.SettingsDiagnostics -> "诊断"
        AppRoute.SettingsLogs -> "运行日志"
        AppRoute.SettingsCallNotifications -> "通知设置"
        AppRoute.SettingsAbout -> "关于"
    }

private data object RootBackInfo : NavigationEventInfo()
