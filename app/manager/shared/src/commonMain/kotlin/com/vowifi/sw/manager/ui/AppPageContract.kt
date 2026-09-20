package com.vowifi.sw.manager.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.ui.navigation.AppRoute

internal data class RootBottomContentInset(val value: Dp)

internal val LocalRootBottomContentInset = staticCompositionLocalOf { RootBottomContentInset(0.dp) }

internal data class RootBottomLayoutInsets(
    val viewportBottomInset: Dp,
    val pageContentBottomInset: Dp,
)

internal fun rootBottomLayoutInsets(
    mode: BottomChromeMode,
    scaffoldBottomInset: Dp,
    navigationBarInset: Dp = 0.dp,
): RootBottomLayoutInsets {
    val occupancy = when (mode) {
        BottomChromeMode.FLOATING -> {
            val floatingOccupancy = NativeNavigationLayout.height + NativeNavigationLayout.bottomGap + navigationBarInset
            maxOf(scaffoldBottomInset, floatingOccupancy)
        }
        BottomChromeMode.NORMAL -> scaffoldBottomInset
    }
    return RootBottomLayoutInsets(
        viewportBottomInset = 0.dp,
        pageContentBottomInset = occupancy,
    )
}

internal val LocalSubpageTopContentInset = staticCompositionLocalOf { 0.dp }

internal val LocalSubpageBottomContentInset = staticCompositionLocalOf { 0.dp }

internal fun pageLocalBackdropEnabled(showTopBar: Boolean, effectiveBlur: Boolean): Boolean =
    showTopBar && effectiveBlur

internal enum class RootTopBarGeometry {
    LARGE_COLLAPSIBLE,
}

internal enum class AppPageWindowInsets {
    ROOT_HORIZONTAL_SYSTEM_BARS,
    EMBEDDED_ZERO,
}

internal const val TOP_BAR_BACKDROP_OVERLAP_THRESHOLD = 0.01f

internal data class AppPageContract(
    val verticalScrollOwnerCount: Int,
    val usesLazyListState: Boolean,
    val usesNestedScroll: Boolean,
    val usesOverscroll: Boolean,
    val usesScrollEndHaptic: Boolean,
    val dynamicTopBarBlend: Boolean,
    val rootTopBarGeometry: RootTopBarGeometry,
    val minimumTopBarBlendAlpha: Float,
    val maximumTopBarBlendAlpha: Float,
    val externalVisualBaseline: Boolean,
    val fillsAvailableViewport: Boolean = false,
    val listFillsRemainingViewportBeforeOverscroll: Boolean = false,
)

internal fun appPageContract(hapticsEnabled: Boolean = true): AppPageContract = AppPageContract(
    verticalScrollOwnerCount = 1,
    usesLazyListState = true,
    usesNestedScroll = true,
    usesOverscroll = true,
    usesScrollEndHaptic = hapticsEnabled,
    dynamicTopBarBlend = true,
    rootTopBarGeometry = RootTopBarGeometry.LARGE_COLLAPSIBLE,
    minimumTopBarBlendAlpha = 0.72f,
    maximumTopBarBlendAlpha = 0.92f,
    externalVisualBaseline = false,
    fillsAvailableViewport = true,
    listFillsRemainingViewportBeforeOverscroll = true,
)

internal fun topBarBlendAlpha(overlapFraction: Float): Float {
    val contract = appPageContract()
    val min = contract.minimumTopBarBlendAlpha
    val max = contract.maximumTopBarBlendAlpha
    val fraction = overlapFraction.coerceIn(0f, 1f)
    return min + (max - min) * fraction
}

@Suppress("UNUSED_PARAMETER")
internal fun topBarBackdropEnabled(overlapFraction: Float): Boolean = true

internal fun rootTopBarContainerColor(
    renderer: ChromeBlurRenderer,
    canvas: Color,
): Color = when (renderer) {
    ChromeBlurRenderer.DRAW_BACKDROP -> Color.Transparent
    ChromeBlurRenderer.TRANSPARENT -> canvas.copy(alpha = 1f)
}

internal data class HomeFactsLayoutContract(
    val usesHomeLocalCompactRow: Boolean = true,
    val usesGlobalFactRow: Boolean = false,
    val usesBasicComponent56DpMinimum: Boolean = false,
    val horizontalPadding: Dp = 12.dp,
    val verticalPadding: Dp = 8.dp,
    val rowGap: Dp = 4.dp,
    val innerLabelValueGap: Dp = 4.dp,
    val labelFontSize: TextUnit = 12.sp,
    val labelLineHeight: TextUnit = 16.sp,
    val valueFontSize: TextUnit = 14.sp,
    val valueLineHeight: TextUnit = 20.sp,
)

internal val homeFactsLayoutContractInstance = HomeFactsLayoutContract()

internal fun homeFactsLayoutContract(): HomeFactsLayoutContract = homeFactsLayoutContractInstance

internal data class HomeLayoutContract(
    val contentTopInsetDp: Int = 12,
    val cardGapDp: Int = 12,
    val topGridInternalGapDp: Int = 12,
    val factsUseGlobalFactRow: Boolean = false,
    val factsUseBasicComponent56DpMinimum: Boolean = false,
    val factsUseHomeLocalCompactRow: Boolean = true,
)

internal val homeLayoutContract = HomeLayoutContract()

internal fun homeContentTopInset(): Dp = homeLayoutContract.contentTopInsetDp.dp

internal fun pageHorizontalInset(showTopBar: Boolean, availableWidth: Dp, override: Dp? = null): Dp =
    override ?: if (showTopBar) settingsHubLayoutContract().horizontalInset else if (availableWidth >= 600.dp) 24.dp else 16.dp

internal fun appPageWindowInsets(showTopBar: Boolean): AppPageWindowInsets = if (showTopBar) {
    AppPageWindowInsets.ROOT_HORIZONTAL_SYSTEM_BARS
} else {
    AppPageWindowInsets.EMBEDDED_ZERO
}

internal fun appPageNestedScrollEnabled(showTopBar: Boolean): Boolean = showTopBar

internal fun appRootTitle(root: AppRoute.Root): String = when (root) {
    AppRoute.Home -> "SoftVoWiFi"
    AppRoute.Calls -> "通话"
    AppRoute.Messages -> "短信"
    AppRoute.Settings -> "设置"
}
