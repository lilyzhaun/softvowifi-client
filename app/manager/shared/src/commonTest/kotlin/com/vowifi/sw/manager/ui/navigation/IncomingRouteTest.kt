package com.vowifi.sw.manager.ui.navigation

import com.vowifi.sw.manager.ui.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class IncomingRouteTest {
    @Test
    fun initialIntentDefaultsToHomeAndAcceptsEveryDestinationAlias() {
        assertEquals(AppRoute.Home, IncomingRoute.initial(null))
        assertEquals(AppRoute.Home, IncomingRoute.initial("status"))
        assertEquals(AppRoute.Home, IncomingRoute.initial("home"))
        assertEquals(AppRoute.Calls, IncomingRoute.initial("calls"))
        assertEquals(AppRoute.Messages, IncomingRoute.initial("messages"))
        listOf("configuration", "diagnostics", "settings").forEach { alias ->
            assertEquals(AppRoute.Settings, IncomingRoute.initial(alias))
        }
        assertEquals(AppRoute.Home, IncomingRoute.initial("malformed"))
    }

    @Test
    fun newIntentAcceptsEveryDestinationAliasWithoutUsingInitialFallback() {
        val expected = mapOf(
            "status" to AppRoute.Home,
            "home" to AppRoute.Home,
            "calls" to AppRoute.Calls,
            "messages" to AppRoute.Messages,
            "configuration" to AppRoute.Settings,
            "diagnostics" to AppRoute.Settings,
            "settings" to AppRoute.Settings,
        )

        expected.forEach { (alias, route) ->
            assertEquals(route, IncomingRoute.onNewIntent(alias))
        }
    }

    @Test
    fun newIntentIgnoresMalformedValuesWithoutResettingCurrentRoute() {
        val current = AppNavigator.push(
            AppNavigationState.initial(AppRoute.Settings),
            AppRoute.SettingsDiagnostics,
        )

        listOf(null, "", " calls", "CALLS", "malformed").forEach { alias ->
            assertNull(IncomingRoute.onNewIntent(alias))
            assertSame(current, IncomingRoute.applyNewIntent(current, alias))
        }
    }

    @Test
    fun repeatedNewIntentSelectsExactlyOneRootWithoutGrowingTheStack() {
        var state = AppNavigationState.initial(AppDestination.SETTINGS.rootRoute)
        state = AppNavigator.push(state, AppRoute.SettingsDiagnostics)

        state = IncomingRoute.applyNewIntent(state, "calls")
        val repeated = IncomingRoute.applyNewIntent(state, "calls")

        assertEquals(listOf(AppRoute.Calls), state.backStack)
        assertSame(state, repeated)
    }
}
