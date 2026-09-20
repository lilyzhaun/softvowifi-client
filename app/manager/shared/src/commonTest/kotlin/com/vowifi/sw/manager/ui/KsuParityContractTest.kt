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
        assertFalse(contract.floatingChildIntrinsicWidth)
        assertFalse(contract.pagerConsumesBottomPadding)
        assertTrue(contract.rootContentConsumesBottomPadding)
        assertTrue(contract.contentUnderlaysFloatingHost)
        assertTrue(contract.normalModeReservesBottomSpace)
        assertTrue(contract.normalModeFullWidth)
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
        val supported = backdropOwnershipContract(effectiveBlur = true, subpageVisible = false)
        val fallback = backdropOwnershipContract(effectiveBlur = false, subpageVisible = false)

        assertEquals(1, supported.rootPagerLayerBackdropAttachments)
        assertEquals(0, supported.rootPagerBottomBarConsumers)
        assertEquals(0, supported.bottomBarLocalBackdropAttachments)
        assertEquals(0, supported.bottomBarLocalBackdropConsumers)
        assertEquals(0, supported.bottomBarDrawBackdropCallCount)
        assertEquals(0, supported.rootPagerOtherConsumers)
        assertEquals(1, supported.rootPageLocalBackdropAttachments)
        assertEquals(1, supported.rootPageTopBarConsumers)
        assertEquals(0, supported.textureEffectCount)
        assertEquals(0, fallback.drawBackdropConsumerCount)
        assertEquals(0, fallback.bottomBarLocalBackdropAttachments)
    }
}
