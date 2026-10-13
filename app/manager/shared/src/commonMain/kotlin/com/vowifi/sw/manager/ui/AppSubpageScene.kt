package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

internal data class AppSubpageSceneContract(
    val title: String,
    val entrySceneCount: Int,
    val sceneOwnsTopBar: Boolean,
    val sceneOwnsContent: Boolean,
    val opaqueContainer: Boolean,
    val globalSubpageTopBarCount: Int,
    val rootPlaceholderOwnsContent: Boolean,
    val pushPopMovesSceneAtomically: Boolean,
    val contentRootAppliesScaffoldPadding: Boolean,
    val bodyDrawsFromOrigin: Boolean,
    val topBarOverlaysBody: Boolean,
    val listConsumesVerticalInsetViaContentPadding: Boolean,
    val listConsumesBottomInsetViaContentPadding: Boolean,
    val topBarBlendAlpha: Float,
)

internal fun appSubpageSceneContract(route: AppRoute.Subpage = AppRoute.SettingsAppearance) =
    AppSubpageSceneContract(
        title = if (route == AppRoute.Dialpad) "拨号" else route.topBarTitle,
        entrySceneCount = 1,
        sceneOwnsTopBar = true,
        sceneOwnsContent = true,
        opaqueContainer = true,
        globalSubpageTopBarCount = 0,
        rootPlaceholderOwnsContent = false,
        pushPopMovesSceneAtomically = true,
        contentRootAppliesScaffoldPadding = false,
        bodyDrawsFromOrigin = true,
        topBarOverlaysBody = route != AppRoute.Dialpad,
        listConsumesVerticalInsetViaContentPadding = route != AppRoute.Dialpad,
        listConsumesBottomInsetViaContentPadding = route != AppRoute.Dialpad,
        topBarBlendAlpha = if (route is AppRoute.MessageThread || route == AppRoute.NewMessage) 0.92f else 0f,
    )

internal data class SubpageTopBarContract(
    val usesLeadingNavigationIcon: Boolean,
    val backTargetDp: Int,
    val trailingBackActionCount: Int,
    val titleOpticallyConstrained: Boolean,
)

internal fun subpageTopBarContract(route: AppRoute.Subpage): SubpageTopBarContract {
    require(route.topBarTitle.isNotBlank())
    return SubpageTopBarContract(true, 48, 0, true)
}

internal data class SubpageTopBarAction(
    val icon: ImageVector,
    val label: String,
    val action: ManagerAction,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

internal val LocalSubpageTopBarAction = staticCompositionLocalOf<MutableState<SubpageTopBarAction?>?> { null }

internal fun subpageTitleOverride(route: AppRoute.Subpage, messageThreadTitle: String?): String? =
    messageThreadTitle.takeIf { route is AppRoute.MessageThread }

@Composable
internal fun AppSubpageScene(
    route: AppRoute.Subpage,
    chromeSpec: AppChromeSpec,
    titleOverride: String? = null,
    routeActive: Boolean = true,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (route == AppRoute.Dialpad) {
        CompositionLocalProvider(LocalPhoneBack provides onBack, LocalAppRouteActive provides routeActive) { content() }
        return
    }
    val contract = appSubpageSceneContract(route)
    val colors = LocalSoftVoWiFiColors.current
    val communication = route is AppRoute.MessageThread || route == AppRoute.NewMessage || route is AppRoute.CallHistoryDetail
    val pageControl = remember(route) { mutableStateOf<CommunicationPageControl?>(null) }
    val topBarAction = remember(route) { mutableStateOf<SubpageTopBarAction?>(null) }
    val backdrop = if (communication) rememberCommunicationBackdrop()
        else if (chromeSpec.effectiveBlur) rememberSoftVoWiFiBackdrop(chromeSpec) else null
    val currentTitle = titleOverride ?: route.topBarTitle
    val title = if (route is AppRoute.MessageThread) rememberMessageRouteFrame(currentTitle, route, routeActive) else currentTitle
    val expandedPeer = route is AppRoute.MessageThread && (LocalDensity.current.fontScale > 1.3f || title.length > 16)
    val phoneDetail = communication
    val canvas = if (phoneDetail) phoneColors().canvas else colors.canvas
    PhoneStyle(enabled = phoneDetail) {
    Scaffold(
        modifier = Modifier.fillMaxSize().background(canvas.copy(alpha = 1f)),
        topBar = {
            if (communication) CompositionLocalProvider(LocalCommunicationBackdrop provides backdrop) {
                CommunicationNavigation(title, leading = {
                    CommunicationIconAction(MiuixIcons.Back, "返回", onBack, enabled = routeActive)
                }, trailing = { pageControl.value?.let { CommunicationIconAction(it.icon, it.label, it.action, enabled = routeActive) } },
                    subtitle = pageControl.value?.subtitle)
            } else {
            SmallTopAppBar(
                modifier = chromeSurfaceModifier(Modifier, chromeSpec, backdrop, RectangleShape, blendAlpha = contract.topBarBlendAlpha),
                title = if (expandedPeer) "" else title,
                color = if (!chromeSpec.effectiveBlur && contract.topBarBlendAlpha > 0f) colors.canvas else Color.Transparent,
                titleColor = colors.textPrimary,
                navigationIcon = {
                    if (communication) CompositionLocalProvider(LocalCommunicationBackdrop provides backdrop) {
                        CommunicationIconAction(MiuixIcons.Back, "返回", onBack, enabled = routeActive)
                    } else ManagerIconAction(MiuixIcons.Back, "返回", ManagerAction.Navigate, onBack, enabled = routeActive)
                },
                actions = {
                    topBarAction.value?.let {
                        ManagerIconAction(it.icon, it.label, it.action, it.onClick, enabled = routeActive && it.enabled)
                    }
                },
                bottomContent = {
                    if (expandedPeer) Text(title, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, color = colors.textPrimary)
                },
            )
            }
        },
        containerColor = canvas.copy(alpha = 1f),
    ) { padding ->
        val capture = backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier
        Box(
            Modifier.fillMaxSize().background(canvas.copy(alpha = 1f)).then(capture),
        ) {
            CompositionLocalProvider(
                LocalSubpageTopContentInset provides padding.calculateTopPadding(),
                LocalSubpageBottomContentInset provides padding.calculateBottomPadding(),
                LocalAppRouteActive provides routeActive,
                LocalCommunicationPageControl provides pageControl.takeIf { communication },
                LocalSubpageTopBarAction provides topBarAction.takeIf { !communication },
            ) {
                content()
            }
        }
    }
    }
}
