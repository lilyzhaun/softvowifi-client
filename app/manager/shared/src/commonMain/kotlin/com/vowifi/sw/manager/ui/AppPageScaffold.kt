package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.movableContentOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

@Composable
internal fun AppPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
    contentHorizontalInset: Dp? = null,
    contentTopInset: Dp = if (showTopBar) 12.dp else 8.dp,
    communicationStyle: Boolean = false,
    scrollResetCondition: Any? = null,
    refreshController: ManagerRefreshController? = null,
    refreshIndicator: State<Boolean>? = null,
    contentAlpha: State<Float>? = null,
    emptyAlpha: State<Float>? = null,
    actions: @Composable RowScope.() -> Unit = {},
    refreshRotation: State<Float>? = null,
    content: LazyListScope.() -> Unit,
) {
    appPageContract()
    val listState = rememberLazyListState()
    ResetManagerScrollOnConditionChange(listState, scrollResetCondition)
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    if (showTopBar) RegisterRootScrollToTop(listState,
        needsExpansion = { scrollBehavior.state.heightOffset != 0f }) {
        animate(scrollBehavior.state.heightOffset, 0f,
            animationSpec = tween(MotionTokens.DurBase, easing = MotionTokens.EaseStandard)) { value, _ ->
            scrollBehavior.state.heightOffset = value
        }
        scrollBehavior.state.contentOffset = 0f
    }
    val overlapFraction by remember {
        derivedStateOf { scrollBehavior.state.overlappedFraction }
    }
    val colors = LocalSoftVoWiFiColors.current
    val canvas = if (communicationStyle) communicationColors().list else colors.canvas
    val chrome = LocalSoftVoWiFiChromeEnvironment.current
    val pageBackdrop = if (communicationStyle && showTopBar) {
        rememberCommunicationBackdrop()
    } else if (pageLocalBackdropEnabled(showTopBar, chrome.spec.effectiveBlur)) {
        rememberSoftVoWiFiBackdrop(chrome.spec)
    } else {
        null
    }
    val rootBottomInset = LocalRootBottomContentInset.current
    val subpageTopContentInset = LocalSubpageTopContentInset.current
    val subpageBottomContentInset = LocalSubpageBottomContentInset.current
    BoxWithConstraints(modifier.fillMaxSize()) {
            val horizontalInset = pageHorizontalInset(showTopBar, maxWidth, contentHorizontalInset ?: if (communicationStyle) 16.dp else null)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (showTopBar) {
                    CompositionLocalProvider(LocalCommunicationBackdrop provides pageBackdrop) {
                        if (communicationStyle) CommunicationRootHeader(title, actions)
                        else AppPageTopBar(title, scrollBehavior, overlapFraction, chrome.spec, pageBackdrop, actions)
                    }
                }
            },
            containerColor = canvas.copy(alpha = 1f),
            contentWindowInsets = when (appPageWindowInsets(showTopBar)) {
                AppPageWindowInsets.ROOT_HORIZONTAL_SYSTEM_BARS ->
                    WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
                AppPageWindowInsets.EMBEDDED_ZERO -> WindowInsets(0, 0, 0, 0)
            },
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val capture = pageBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier
            val nestedScrollModifier = if (appPageNestedScrollEnabled(showTopBar) && !communicationStyle) {
                Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            } else {
                Modifier
            }
            Box(Modifier.fillMaxSize().background(canvas.copy(alpha = 1f)).then(capture)) {
                val indicator = refreshIndicator ?: refreshController?.let { rememberManagerRefreshIndicator(it) }
                val padding = rememberUpdatedState(PaddingValues(
                        start = innerPadding.calculateStartPadding(layoutDirection) + horizontalInset,
                        top = innerPadding.calculateTopPadding() + subpageTopContentInset + contentTopInset,
                        end = innerPadding.calculateEndPadding(layoutDirection) + horizontalInset,
                        bottom = innerPadding.calculateBottomPadding() + subpageBottomContentInset + rootBottomInset.value + 12.dp,
                    ))
                val latestContent = rememberUpdatedState(content)
                val latestNestedScroll = rememberUpdatedState(nestedScrollModifier)
                val latestAlpha = rememberUpdatedState(contentAlpha)
                val latestEmptyAlpha = rememberUpdatedState(emptyAlpha)
                val headerExtent = remember { mutableFloatStateOf(0f) }
                // One scroll/list/key owner survives removal of the library gesture wrapper.
                val body = remember {
                    movableContentOf {
                        Box(Modifier.fillMaxSize().onGloballyPositioned {
                            val height = it.positionInParent().y.coerceAtLeast(0f)
                            if (headerExtent.floatValue != height) headerExtent.floatValue = height
                        }) {
                            LazyColumn(
                                state = listState,
                                overscrollEffect = null,
                                modifier = Modifier.fillMaxSize().widthIn(max = 960.dp).align(Alignment.TopCenter)
                                    .managerVerticalScrollFeedback().then(latestNestedScroll.value)
                                    .graphicsLayer {
                                        alpha = (latestAlpha.value?.value ?: 1f) * (latestEmptyAlpha.value?.value ?: 1f)
                                    },
                                contentPadding = padding.value,
                                content = { latestContent.value.invoke(this) },
                            )
                        }
                    }
                }
                val pullState = refreshController?.let { rememberPullToRefreshState() }
                // A closed admission gate must still deliver false to the same library owner
                // and let its completion animation finish before removing the gesture wrapper.
                val finishingRefresh = pullState?.refreshState == RefreshState.Refreshing ||
                    pullState?.refreshState == RefreshState.RefreshComplete
                if (refreshController != null && pullState != null &&
                    (refreshController.available || refreshController.running || indicator?.value == true || finishingRefresh)) {
                    val angle = refreshRotation ?: rememberManagerRefreshRotation(refreshController)
                    PullToRefresh(
                        isRefreshing = indicator?.value == true,
                        onRefresh = { refreshController.request() },
                        modifier = Modifier.fillMaxSize().semantics {
                            contentDescription = "下拉刷新进度"
                            // Gesture progress comes from the actual public library state, not
                            // the request's completion or an app copy of its private enum.
                            progressBarRangeInfo = ProgressBarRangeInfo(pullState.pullProgress, 0f..1f)
                            stateDescription = if (refreshController.running) "正在刷新" else "下拉刷新"
                        },
                        pullToRefreshState = pullState,
                        contentPadding = PaddingValues(top = padding.value.calculateTopPadding()),
                        color = canvas.copy(alpha = 1f),
                        refreshTexts = emptyList(),
                    ) { body() }
                    ManagerCompactRefreshIndicator(pullState, refreshController, indicator?.value == true,
                        angle, headerExtent, padding)
                } else body()
            }
        }
    }
}

@Composable
private fun AppPageTopBar(
    title: String,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    overlapFraction: Float,
    chromeSpec: AppChromeSpec,
    pageBackdrop: LayerBackdrop?,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = LocalSoftVoWiFiColors.current
    val blendAlpha = topBarBlendAlpha(overlapFraction)
    val modifier = chromeSurfaceModifier(
        Modifier,
        chromeSpec,
        pageBackdrop.takeIf { topBarBackdropEnabled(overlapFraction) },
        RectangleShape,
        blendAlpha = blendAlpha,
    )
    val renderer = chromeSurfaceFallbackRenderer(chromeSpec.effectiveBlur, pageBackdrop != null)
    val background = rootTopBarContainerColor(renderer, colors.canvas)
    TopAppBar(
        title = title,
        modifier = modifier,
        color = background,
        titleColor = colors.textPrimary,
        largeTitleColor = colors.textPrimary,
        scrollBehavior = scrollBehavior,
        actions = actions,
    )
}
