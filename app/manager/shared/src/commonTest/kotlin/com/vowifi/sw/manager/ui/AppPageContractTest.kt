package com.vowifi.sw.manager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPageContractTest {
    @Test
    fun pageUsesOneScrollOwnerAndDynamicOverlapDrivenChrome() {
        val contract = appPageContract()
        val noHapticContract = appPageContract(hapticsEnabled = false)

        assertEquals(1, contract.verticalScrollOwnerCount)
        assertTrue(contract.usesLazyListState)
        assertTrue(contract.usesNestedScroll)
        assertTrue(contract.usesOverscroll)
        assertTrue(contract.usesScrollEndHaptic)
        assertTrue(contract.fillsAvailableViewport)
        assertTrue(contract.listFillsRemainingViewportBeforeOverscroll)
        assertFalse(noHapticContract.usesScrollEndHaptic)
        assertTrue(contract.dynamicTopBarBlend)
        assertEquals(RootTopBarGeometry.LARGE_COLLAPSIBLE, contract.rootTopBarGeometry)
        assertEquals(0.72f, topBarBlendAlpha(overlapFraction = 0f))
        assertEquals(0.82f, topBarBlendAlpha(overlapFraction = 0.5f), absoluteTolerance = 0.0001f)
        assertEquals(0.92f, topBarBlendAlpha(overlapFraction = 1f))
        assertEquals(contract.maximumTopBarBlendAlpha, topBarBlendAlpha(overlapFraction = 1f))
        assertTrue(topBarBackdropEnabled(overlapFraction = 0f))
        assertTrue(topBarBackdropEnabled(overlapFraction = TOP_BAR_BACKDROP_OVERLAP_THRESHOLD))
        assertTrue(topBarBackdropEnabled(overlapFraction = TOP_BAR_BACKDROP_OVERLAP_THRESHOLD + 0.001f))
        val canvas = Color(0xFF101010)
        assertEquals(Color.Transparent, rootTopBarContainerColor(ChromeBlurRenderer.DRAW_BACKDROP, canvas))
        assertEquals(canvas.copy(alpha = 1f), rootTopBarContainerColor(ChromeBlurRenderer.TRANSPARENT, canvas))
        assertEquals(12.dp, homeContentTopInset())
        assertEquals(12, homeLayoutContract.contentTopInsetDp)
        assertEquals(12, homeLayoutContract.cardGapDp)
        assertEquals(12, homeLayoutContract.topGridInternalGapDp)
        assertFalse(homeLayoutContract.factsUseGlobalFactRow)
        assertFalse(homeLayoutContract.factsUseBasicComponent56DpMinimum)
        assertTrue(homeLayoutContract.factsUseHomeLocalCompactRow)
        assertEquals(AppPageWindowInsets.ROOT_HORIZONTAL_SYSTEM_BARS, appPageWindowInsets(showTopBar = true))
        assertEquals(AppPageWindowInsets.EMBEDDED_ZERO, appPageWindowInsets(showTopBar = false))
        assertTrue(appPageNestedScrollEnabled(showTopBar = true))
        assertFalse(appPageNestedScrollEnabled(showTopBar = false))
        assertFalse(contract.externalVisualBaseline)
        assertTrue(pageLocalBackdropEnabled(showTopBar = true, effectiveBlur = true))
        assertFalse(pageLocalBackdropEnabled(showTopBar = false, effectiveBlur = true))
        assertFalse(pageLocalBackdropEnabled(showTopBar = true, effectiveBlur = false))
        assertFalse(pageLocalBackdropEnabled(showTopBar = false, effectiveBlur = false))
    }
}
