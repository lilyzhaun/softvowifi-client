package com.vowifi.sw.manager.ui

import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootPageLayoutContractTest {
    @Test
    fun bottomChromeVisibilityUnmountsHitTestLayerWithoutExitAnimation() {
        assertEquals(BottomChromeComposition.PRESENT, bottomChromeComposition(visible = true))
        assertEquals(BottomChromeComposition.ABSENT, bottomChromeComposition(visible = false))
        val contract = bottomChromeVisibilityContract()
        assertFalse(contract.hiddenHitTestLayer)
        assertFalse(contract.hiddenExitAnimation)
    }

    @Test
    fun floatingModeReservesMinimumBarOccupancyWhenScaffoldReportsZeroOrInsufficient() {
        val navigationBarInset = 24.dp
        val expectedMinOccupancy = NativeNavigationLayout.height + NativeNavigationLayout.bottomGap + navigationBarInset

        assertEquals(100.dp, expectedMinOccupancy)

        val measuredZero = rootBottomLayoutInsets(
            mode = BottomChromeMode.FLOATING,
            scaffoldBottomInset = 0.dp,
            navigationBarInset = navigationBarInset,
        )
        assertEquals(0.dp, measuredZero.viewportBottomInset)
        assertEquals(expectedMinOccupancy, measuredZero.pageContentBottomInset)

        val measuredInsufficient = rootBottomLayoutInsets(
            mode = BottomChromeMode.FLOATING,
            scaffoldBottomInset = 50.dp,
            navigationBarInset = navigationBarInset,
        )
        assertEquals(0.dp, measuredInsufficient.viewportBottomInset)
        assertEquals(expectedMinOccupancy, measuredInsufficient.pageContentBottomInset)

        val measuredSufficient = rootBottomLayoutInsets(
            mode = BottomChromeMode.FLOATING,
            scaffoldBottomInset = 120.dp,
            navigationBarInset = navigationBarInset,
        )
        assertEquals(0.dp, measuredSufficient.viewportBottomInset)
        assertEquals(120.dp, measuredSufficient.pageContentBottomInset)
    }

    @Test
    fun normalModeConsumesMeasuredScaffoldInsetDirectlyWithoutArtificialFloor() {
        val normalMeasured = rootBottomLayoutInsets(
            mode = BottomChromeMode.NORMAL,
            scaffoldBottomInset = 80.dp,
            navigationBarInset = 24.dp,
        )
        assertEquals(0.dp, normalMeasured.viewportBottomInset)
        assertEquals(80.dp, normalMeasured.pageContentBottomInset)

        val normalZero = rootBottomLayoutInsets(
            mode = BottomChromeMode.NORMAL,
            scaffoldBottomInset = 0.dp,
            navigationBarInset = 24.dp,
        )
        assertEquals(0.dp, normalZero.viewportBottomInset)
        assertEquals(0.dp, normalZero.pageContentBottomInset)
    }

    @Test
    fun insetsNeverDoubleCountAcrossAllModesAndInputs() {
        val testCases = listOf(
            Triple(BottomChromeMode.FLOATING, 0.dp, 24.dp),
            Triple(BottomChromeMode.FLOATING, 100.dp, 24.dp),
            Triple(BottomChromeMode.FLOATING, 120.dp, 24.dp),
            Triple(BottomChromeMode.NORMAL, 0.dp, 24.dp),
            Triple(BottomChromeMode.NORMAL, 80.dp, 24.dp),
        )

        testCases.forEach { (mode, scaffoldInset, navInset) ->
            val insets = rootBottomLayoutInsets(mode, scaffoldInset, navInset)
            val expectedOccupancy = when (mode) {
                BottomChromeMode.FLOATING -> {
                    maxOf(
                        scaffoldInset,
                        NativeNavigationLayout.height + NativeNavigationLayout.bottomGap + navInset,
                    )
                }
                BottomChromeMode.NORMAL -> scaffoldInset
            }
            assertEquals(0.dp, insets.viewportBottomInset)
            assertEquals(expectedOccupancy, insets.pageContentBottomInset)
        }
    }

    @Test
    fun subpageHidesBottomBarAndMaintainsFullViewportSceneOwnership() {
        val subpage = AppRoute.SettingsAppearance
        val subpageScene = appSubpageSceneContract(subpage)

        assertTrue(subpageScene.sceneOwnsContent)
        assertTrue(subpageScene.sceneOwnsTopBar)
        assertTrue(subpageScene.bodyDrawsFromOrigin)
        assertTrue(subpageScene.listConsumesBottomInsetViaContentPadding)
        assertFalse(subpageScene.contentRootAppliesScaffoldPadding)

        val subpageNavState = AppNavigator.push(AppNavigationState.initial(AppRoute.Settings), subpage)
        val subpagePolicy = AppShellPolicy.forState(subpageNavState)

        assertFalse(subpagePolicy.showBottomBar)
        assertEquals(AppShellContentHost.NavDisplay, subpagePolicy.contentHost)
    }
}
