package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppRootPagerTest {
    @Test
    fun pagerOwnsExactlyFourRootsWithStablePreloadAndOneStateOwner() {
        val contract = appRootPagerContract()

        assertEquals(AppRoute.rootRoutes, contract.pages)
        assertEquals(3, contract.beyondViewportPageCount)
        assertEquals(1, contract.stateOwnerCount)
        assertFalse(contract.rootContentCrossfade)
        assertEquals(0, contract.selectionDurationMillis)
        assertEquals(RootPagerEasing.SPRING, contract.easing)
        assertEquals(322.27f, contract.springStiffness)
        assertEquals(0.9f, contract.springDampingRatio)
        assertEquals(0.5f, contract.springVisibilityThreshold)
    }

    @Test
    fun selectionCancelsPreviousAnimationWhileSnapIsImmediate() {
        val coordinator = AppRootPagerCoordinator(initialPage = 0)

        val calls = coordinator.select(page = 1, mode = RootSelectionMode.ANIMATE)
        val settings = coordinator.select(page = 3, mode = RootSelectionMode.ANIMATE)
        val ringing = coordinator.select(page = 1, mode = RootSelectionMode.SNAP)

        assertFalse(calls.cancelPrevious)
        assertTrue(calls.usesSpring)
        assertEquals(0, calls.durationMillis)
        assertTrue(settings.cancelPrevious)
        assertTrue(settings.usesSpring)
        assertEquals(0, settings.durationMillis)
        assertTrue(ringing.cancelPrevious)
        assertFalse(ringing.usesSpring)
        assertEquals(0, ringing.durationMillis)
        assertEquals(1, coordinator.selectedPage)
    }

    @Test
    fun selectingSettingsFromHomeUpdatesSelectedPageBeforeAnimationAndUsesSpringNotDistanceTween() {
        val coordinator = AppRootPagerCoordinator(initialPage = 0)

        val plan = coordinator.select(page = 3, mode = RootSelectionMode.ANIMATE)

        assertEquals(3, coordinator.selectedPage)
        assertTrue(coordinator.inFlight)
        assertTrue(coordinator.hasProgrammaticTarget)
        assertTrue(plan.usesSpring)
        assertEquals(0, plan.durationMillis)
        assertFalse(plan.durationMillis == 400)
    }

    @Test
    fun programmaticTargetSurvivesOldPageObservationUntilSettle() {
        val coordinator = AppRootPagerCoordinator(initialPage = 0)

        coordinator.select(page = 2, mode = RootSelectionMode.ANIMATE)
        coordinator.observePager(targetPage = 0, settledPage = 0, scrollInProgress = true)
        assertEquals(2, coordinator.selectedPage)

        coordinator.observePager(targetPage = 2, settledPage = 2, scrollInProgress = false)
        assertEquals(2, coordinator.selectedPage)
        assertFalse(coordinator.hasProgrammaticTarget)
    }

    @Test
    fun interruptedProgrammaticSelectionAdoptsTheActuallySettledPage() {
        val coordinator = AppRootPagerCoordinator(initialPage = 0)

        coordinator.select(page = 3, mode = RootSelectionMode.ANIMATE)
        coordinator.observePager(targetPage = 3, settledPage = 0, scrollInProgress = true)
        coordinator.observePager(targetPage = 1, settledPage = 1, scrollInProgress = false)

        assertEquals(1, coordinator.selectedPage)
        assertFalse(coordinator.hasProgrammaticTarget)
        assertFalse(coordinator.inFlight)
    }

    @Test
    fun pagerOriginatedNavigationDoesNotStartASecondProgrammaticAnimation() {
        assertFalse(shouldSynchronizeExternalRoot(selectedPage = 3, requestedPage = 3, forceSnap = false))
        assertTrue(shouldSynchronizeExternalRoot(selectedPage = 2, requestedPage = 3, forceSnap = false))
        assertTrue(shouldSynchronizeExternalRoot(selectedPage = 3, requestedPage = 3, forceSnap = true))
    }

    @Test
    fun userGestureTargetUpdatesSelectionAndSettleClearsFlight() {
        val coordinator = AppRootPagerCoordinator(initialPage = 2)

        coordinator.observePager(targetPage = 3, settledPage = 2, scrollInProgress = true)
        assertEquals(3, coordinator.selectedPage)
        assertTrue(coordinator.inFlight)

        coordinator.observePager(targetPage = 3, settledPage = 3, scrollInProgress = false)
        assertEquals(3, coordinator.selectedPage)
        assertFalse(coordinator.inFlight)
    }

    @Test
    fun snapCancelsFlightAndSelectsImmediately() {
        val coordinator = AppRootPagerCoordinator(initialPage = 0)
        coordinator.select(page = 3, mode = RootSelectionMode.ANIMATE)

        val snap = coordinator.select(page = 1, mode = RootSelectionMode.SNAP)

        assertTrue(snap.cancelPrevious)
        assertEquals(1, coordinator.selectedPage)
        assertFalse(coordinator.inFlight)
        assertFalse(coordinator.hasProgrammaticTarget)
    }

    @Test
    fun pagerItemKeysAreAndroidBundleSaveableIntsNotRouteObjects() {
        val keys = AppRoute.rootRoutes.indices.map(::pagerSaveableKey)
        assertEquals(listOf(0, 1, 2, 3), keys)
        assertEquals(keys, keys.distinct())
        assertEquals("Home", AppRoute.Home::class.simpleName)
        assertEquals("Int", pagerSaveableKey(0)::class.simpleName)
    }

    @Test
    fun swipeIsEnabledOnlyOnRootAndUpdatesTypedRoot() {
        val root = AppNavigationState.initial(AppRoute.Messages)
        val subpage = AppNavigator.push(root, AppRoute.NewMessage)

        assertTrue(rootPagerSwipeEnabled(root))
        assertFalse(rootPagerSwipeEnabled(subpage))
        assertEquals(AppRoute.Settings, rootForPagerPage(3))
        assertEquals(2, pagerPageForRoot(AppRoute.Messages))
    }

    @Test
    fun rootBackReturnsHomeWhileHomeIsIdentityAndSubpagePopsOne() {
        val home = AppNavigationState.initial(AppRoute.Home)
        val settings = AppNavigationState.initial(AppRoute.Settings)
        val appearance = AppNavigator.push(settings, AppRoute.SettingsAppearance)

        assertSame(home, AppNavigator.back(home))
        assertEquals(AppRoute.Home, AppNavigator.back(settings).currentRoot)
        assertEquals(listOf(AppRoute.Settings), AppNavigator.back(appearance).backStack)
    }
}
