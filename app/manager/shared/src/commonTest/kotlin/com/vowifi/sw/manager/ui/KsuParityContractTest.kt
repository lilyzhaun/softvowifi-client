package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KsuParityContractTest {
    @Test
    fun floatingHostOverlaysPagerWithoutPaddingThePager() {
        val contract = ksuRootShellLayoutContract()

        assertTrue(contract.floatingHostFullWidth)
        assertTrue(contract.floatingHostTransparent)
        assertEquals(RootShellChildAlignment.BottomCenter, contract.floatingChildAlignment)
        assertTrue(contract.floatingChildIntrinsicWidth)
        assertFalse(contract.pagerConsumesBottomPadding)
        assertTrue(contract.rootContentConsumesBottomPadding)
        assertTrue(contract.contentUnderlaysFloatingHost)
        assertTrue(contract.normalModeReservesBottomSpace)
        assertTrue(contract.normalModeFullWidth)
    }

    @Test
    fun baseAndIndicatorUseTheSame56DpInnerLayout() {
        val contract = floatingRowLayoutContract()

        assertEquals(64, contract.outerHeightDp)
        assertEquals(4, contract.innerPaddingDp)
        assertEquals(56, contract.baseInnerHeightDp)
        assertEquals(56, contract.indicatorHeightDp)
        assertEquals(76, contract.tabMinimumWidthDp)
        assertTrue(contract.tabMinimumHeightDp >= 48)
        assertEquals(11, contract.labelSizeSp)
        assertEquals(14, contract.labelLineHeightSp)
        assertEquals(TabClickIndication.NONE, contract.tabClickIndication)
        assertEquals(FloatingDragGestureOwner.Indicator, contract.dragGestureOwner)
        assertFalse(contract.baseRowDraggable)
    }

    @Test
    fun settingsRootUsesExactlyFourCompactCardsWithoutSectionLabels() {
        val contract = settingsCardStructureContract()

        assertEquals(4, contract.cardCount)
        assertEquals(
            listOf(
                listOf(SettingsRowId.Root.APPEARANCE, SettingsRowId.Root.CONFIGURATION),
                listOf(SettingsRowId.Root.DIAGNOSTICS, SettingsRowId.Root.OPERATIONAL_LOGS),
                listOf(SettingsRowId.Root.CALL_NOTIFICATION),
                listOf(SettingsRowId.Root.ABOUT),
            ),
            contract.rowsByCard,
        )
        assertEquals(0, contract.smallTitleCount)
        assertEquals(12, contract.horizontalInsetDp)
        assertEquals(12, contract.topAndCardGapDp)
        assertEquals(SettingsRootTopBarStyle.LARGE_COLLAPSIBLE, contract.topBarStyle)
    }

    @Test
    fun backdropOwnershipUsesDrawBackdropWithoutTextureCycles() {
        val supported = safeBackdropContract(effectiveBlur = true)
        val fallback = safeBackdropContract(effectiveBlur = false)

        assertEquals(1, supported.pagerCaptureCount)
        assertEquals(3, supported.bottomBarConsumerCount)
        assertEquals(1, supported.bottomBarLocalCaptureCount)
        assertEquals(1, supported.bottomBarLocalConsumerCount)
        assertEquals(3, supported.bottomBarDrawBackdropCallCount)
        assertEquals(0, supported.rootTopBarConsumerCount)
        assertEquals(1, supported.pageLocalCaptureCount)
        assertEquals(1, supported.pageTopBarConsumerCount)
        assertTrue(supported.usesDrawBackdrop)
        assertFalse(supported.usesTextureBlur)
        assertFalse(fallback.usesDrawBackdrop)
        assertTrue(fallback.opaqueFallback)
        assertEquals(0, fallback.bottomBarLocalCaptureCount)
    }
}
