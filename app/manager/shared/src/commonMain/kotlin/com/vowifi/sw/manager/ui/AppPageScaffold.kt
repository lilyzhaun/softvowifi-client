package com.vowifi.sw.manager.ui

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun AppPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
    contentHorizontalInset: Dp? = null,
    contentTopInset: Dp = if (showTopBar) 12.dp else 8.dp,
    communicationStyle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    appPageContract()
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
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
                val scrollEndModifier = if (chrome.spec.hapticsEnabled) Modifier.scrollEndHaptic() else Modifier
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().widthIn(max = 960.dp).align(Alignment.TopCenter)
                        .then(scrollEndModifier).overScrollVertical()
                        .then(nestedScrollModifier),
                    contentPadding = PaddingValues(
                        start = innerPadding.calculateStartPadding(layoutDirection) + horizontalInset,
                        top = innerPadding.calculateTopPadding() + subpageTopContentInset + contentTopInset,
                        end = innerPadding.calculateEndPadding(layoutDirection) + horizontalInset,
                        bottom = innerPadding.calculateBottomPadding() + subpageBottomContentInset + rootBottomInset.value + 12.dp,
                    ),
                    content = content,
                )
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
