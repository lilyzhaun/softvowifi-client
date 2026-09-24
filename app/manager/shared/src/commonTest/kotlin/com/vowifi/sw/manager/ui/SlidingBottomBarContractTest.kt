package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlidingBottomBarContractTest {
    @Test
    fun geometryAndRenderingMatchKernelSuBaseAndIndicatorContract() {
        val contract = floatingBubbleBarContract()

        assertEquals(64, contract.outerHeightDp)
        assertEquals(56, contract.indicatorHeightDp)
        assertEquals(76, contract.tabMinimumWidthDp)
        assertTrue(contract.tabMinimumHeightDp >= 48)
        assertEquals(4, contract.innerPaddingDp)
        assertEquals(12, contract.bottomPaddingDp)
        assertTrue(contract.addsNavigationBarInset)
        assertEquals(4, contract.rubberBandDp)
        assertEquals(78f / 56f, contract.pressedScale)
        assertEquals(3, contract.renderLayerCount)
        assertTrue(contract.draggable)
        assertTrue(contract.continuousPosition)
        assertTrue(contract.pagerLinked)
        assertTrue(contract.supportsRtl)
        assertTrue(contract.semanticsOnBaseOnly)
        assertEquals(TabClickIndication.NONE, contract.tabClickIndication)
        assertEquals(FloatingDragGestureOwner.Indicator, contract.dragGestureOwner)
        assertFalse(contract.baseRowDraggable)
        assertTrue(contract.horizontalOnlyMotion)
        assertTrue(contract.stableMeasuredBounds)
        assertFalse(contract.usesVerticalExpansion)
        assertTrue(contract.shapeAwareDropShadow)
        assertFalse(contract.animatesShadowElevation)
        assertEquals(10, contract.dropShadowRadiusDp)
        assertTrue(contract.alwaysComposedStableSlot)
        assertFalse(contract.usesUnmountingVisibility)
    }

    @Test
    fun positionModelClampsDragsSettlesAndTranslatesForDirection() {
        assertEquals(0f, clampBubblePosition(-1f, 4))
        assertEquals(3f, clampBubblePosition(4f, 4))
        assertEquals(2.4f, dragBubblePosition(0f, deltaPx = 182.4f, tabWidthPx = 76f, tabCount = 4, rtl = false), 0.0001f)
        assertEquals(2, settleBubbleIndex(2.4f, 4))
        assertEquals(3, settleBubbleIndex(2.6f, 4))
        assertEquals(152f, bubbleTranslationPx(2f, 76f, rtl = false))
        assertEquals(-152f, bubbleTranslationPx(2f, 76f, rtl = true))
    }

    @Test
    fun stateHandlesExternalTargetCancelAndDuplicateCallbacks() {
        val state = SlidingBottomBarModel(initialIndex = 0, tabCount = 4)

        state.beginDrag()
        state.dragBy(deltaPx = 182.4f, tabWidthPx = 76f, rtl = false)
        assertEquals(2.4f, state.position, 0.0001f)
        assertEquals(2, state.endDrag())
        assertEquals(2, state.consumeSelectionCallback(0))
        assertEquals(null, state.consumeSelectionCallback(0))
        assertEquals(null, state.consumeSelectionCallback(2))

        state.externalSelection(3)
        assertEquals(3f, state.targetPosition)
        state.beginDrag()
        state.dragBy(-76f, 76f, rtl = false)
        state.cancelDrag()
        assertEquals(3f, state.targetPosition)
        assertFalse(state.dragActive)
    }

    @Test
    fun rubberBandOffsetStaysBoundedAtBothEdges() {
        assertEquals(0f, rubberBandPanelOffset(12f, totalWidthPx = 0f, rubberBandPx = 4f))
        assertEquals(-4f, rubberBandPanelOffset(-400f, totalWidthPx = 304f, rubberBandPx = 4f))
        assertEquals(4f, rubberBandPanelOffset(400f, totalWidthPx = 304f, rubberBandPx = 4f))
    }

    @Test
    fun absolutePointerSamplesSettleFirstAndSettingsEdgesInLtrAndRtl() {
        assertEquals(0, settleFromAbsolutePointer(pointerInBarPx = 42f, rtl = false))
        assertEquals(3, settleFromAbsolutePointer(pointerInBarPx = 270f, rtl = false))
        assertEquals(0, settleFromAbsolutePointer(pointerInBarPx = 270f, rtl = true))
        assertEquals(3, settleFromAbsolutePointer(pointerInBarPx = 42f, rtl = true))
    }

    @Test
    fun absolutePointerTraversesAllFourTabsInBothDirections() {
        assertEquals(listOf(0, 1, 2, 3), listOf(42f, 118f, 194f, 270f).map { settleFromAbsolutePointer(it, false) })
        assertEquals(listOf(3, 2, 1, 0), listOf(270f, 194f, 118f, 42f).map { settleFromAbsolutePointer(it, false) })
    }

    @Test
    fun absolutePointerMirrorsAllFourTabsInRtl() {
        assertEquals(listOf(0, 1, 2, 3), listOf(270f, 194f, 118f, 42f).map { settleFromAbsolutePointer(it, true) })
        assertEquals(listOf(3, 2, 1, 0), listOf(42f, 118f, 194f, 270f).map { settleFromAbsolutePointer(it, true) })
    }

    @Test
    fun absolutePointerRemovesGrabAndRubberBandOffsetsAtSettingsEdge() {
        assertEquals(
            3f,
            absolutePointerBubblePosition(247f, 12f, 312f, 76f, 4f, 3f, 4, rtl = false),
        )
        assertEquals(
            3f,
            absolutePointerBubblePosition(19f, 12f, 312f, 76f, 4f, 3f, 4, rtl = true),
        )
    }

    @Test
    fun bottomChromeVisibilityUnmountsHitTestLayerWithoutExitAnimation() {
        assertEquals(BottomChromeComposition.PRESENT, bottomChromeComposition(visible = true))
        assertEquals(BottomChromeComposition.ABSENT, bottomChromeComposition(visible = false))

        val contract = bottomChromeVisibilityContract()
        assertFalse(contract.hiddenHitTestLayer)
        assertFalse(contract.hiddenExitAnimation)
    }

    private fun settleFromAbsolutePointer(pointerInBarPx: Float, rtl: Boolean): Int = settleBubbleIndex(
        absolutePointerBubblePosition(
            pointerInBarPx = pointerInBarPx,
            grabOffsetPx = 38f,
            barWidthPx = 312f,
            tabWidthPx = 76f,
            innerPaddingPx = 4f,
            panelOffsetPx = 0f,
            tabCount = 4,
            rtl = rtl,
        ),
        tabCount = 4,
    )
}
