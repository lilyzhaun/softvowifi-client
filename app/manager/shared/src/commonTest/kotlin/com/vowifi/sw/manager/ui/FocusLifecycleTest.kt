package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusLifecycleTest {
    @Test
    fun focusClearReasonPreservesFocusWhileActiveUntilImeWasShownAndThenHidden() {
        val neverShownState = FocusLifecycleState(focused = true, imeShownSinceFocus = false)
        val shownState = FocusLifecycleState(focused = true, imeShownSinceFocus = true)

        assertNull(
            focusClearReason(
                state = neverShownState,
                routeActive = true,
                imeVisible = false,
            ),
        )
        assertEquals(
            FocusClearReason.IME_HIDDEN_AFTER_SHOW,
            focusClearReason(
                state = shownState,
                routeActive = true,
                imeVisible = false,
            ),
        )
    }

    @Test
    fun focusClearReasonClearsImmediatelyOnInactiveRouteRegardlessOfIme() {
        val neverShown = FocusLifecycleState(focused = true, imeShownSinceFocus = false)
        val shown = FocusLifecycleState(focused = true, imeShownSinceFocus = true)

        assertEquals(
            FocusClearReason.ROUTE_INACTIVE,
            focusClearReason(
                state = neverShown,
                routeActive = false,
                imeVisible = true,
            ),
        )
        assertEquals(
            FocusClearReason.ROUTE_INACTIVE,
            focusClearReason(
                state = neverShown,
                routeActive = false,
                imeVisible = false,
            ),
        )
        assertEquals(
            FocusClearReason.ROUTE_INACTIVE,
            focusClearReason(
                state = shown,
                routeActive = false,
                imeVisible = true,
            ),
        )
        assertEquals(
            FocusClearReason.ROUTE_INACTIVE,
            focusClearReason(
                state = shown,
                routeActive = false,
                imeVisible = false,
            ),
        )
    }

    @Test
    fun focusClearReasonIsNoOpWhenUnfocusedOrWhenImeIsCurrentlyVisibleOnActiveRoute() {
        val unfocusedNeverShown = FocusLifecycleState(focused = false, imeShownSinceFocus = false)
        val unfocusedShown = FocusLifecycleState(focused = false, imeShownSinceFocus = true)
        val focusedShown = FocusLifecycleState(focused = true, imeShownSinceFocus = true)
        val focusedNeverShown = FocusLifecycleState(focused = true, imeShownSinceFocus = false)

        assertNull(
            focusClearReason(
                state = unfocusedNeverShown,
                routeActive = true,
                imeVisible = false,
            ),
        )
        assertNull(
            focusClearReason(
                state = unfocusedShown,
                routeActive = false,
                imeVisible = false,
            ),
        )
        assertNull(
            focusClearReason(
                state = focusedShown,
                routeActive = true,
                imeVisible = true,
            ),
        )
        assertNull(
            focusClearReason(
                state = focusedNeverShown,
                routeActive = true,
                imeVisible = true,
            ),
        )
    }

    @Test
    fun routeActivityMatchesCurrentRootOnlyWhenTopRouteIsRoot() {
        val messagesState = AppNavigationState.initial(AppRoute.Messages)

        assertTrue(isRouteActive(messagesState, AppRoute.Messages))
        assertFalse(isRouteActive(messagesState, AppRoute.Settings))
        assertFalse(isRouteActive(messagesState, AppRoute.Home))
        assertFalse(isRouteActive(messagesState, AppRoute.Calls))
    }

    @Test
    fun routeActivityDeactivatesAllRootsWhenSubpageIsTopRoute() {
        val messagesState = AppNavigationState.initial(AppRoute.Messages)
        val threadRoute = AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-fixture-a"))
        val subpageState = AppNavigator.push(messagesState, threadRoute)

        AppRoute.rootRoutes.forEach { root ->
            assertFalse(isRouteActive(subpageState, root))
        }
    }

    @Test
    fun routeActivityMatchesTopSubpageAndRejectsNonmatchingSubpages() {
        val messagesState = AppNavigationState.initial(AppRoute.Messages)
        val threadRoute = AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-fixture-a"))
        val otherThreadRoute = AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-fixture-b"))
        val subpageState = AppNavigator.push(messagesState, threadRoute)

        assertTrue(isRouteActive(subpageState, threadRoute))
        assertFalse(isRouteActive(subpageState, otherThreadRoute))
        assertFalse(isRouteActive(subpageState, AppRoute.NewMessage))
        assertFalse(isRouteActive(subpageState, AppRoute.SettingsAppearance))
    }
}
