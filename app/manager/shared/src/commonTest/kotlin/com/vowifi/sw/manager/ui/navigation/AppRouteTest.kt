package com.vowifi.sw.manager.ui.navigation

import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.topBarTitle
import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppRouteTest {
    @Test
    fun rootsBridgeExhaustivelyToExistingDestinations() {
        val roots = listOf(
            AppRoute.Home,
            AppRoute.Calls,
            AppRoute.Messages,
            AppRoute.Settings,
        )

        assertEquals(AppDestination.entries, roots.map(AppRoute.Root::destination))
        assertEquals(roots, AppDestination.entries.map(AppDestination::rootRoute))
    }

    @Test
    fun everyRouteHasDeterministicSerializationRoundTrip() {
        val routes = listOf(
            AppRoute.Home,
            AppRoute.Calls,
            AppRoute.Messages,
            AppRoute.Settings,
            AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-fixture-a")),
            AppRoute.NewMessage,
            AppRoute.Dialpad,
            AppRoute.SettingsAppearance,
            AppRoute.SettingsConfiguration,
            AppRoute.SettingsDiagnostics,
            AppRoute.SettingsLogs,
            AppRoute.SettingsCallNotifications,
            AppRoute.SettingsAbout,
        )

        routes.forEach { route ->
            val encoded = AppRouteCodec.encode(route)

            assertEquals(RouteDecodeResult.Success(route), AppRouteCodec.decode(encoded))
            assertEquals(encoded, AppRouteCodec.encode(route))
        }
    }

    @Test
    fun conversationHandleStaysOpaqueInRouteString() {
        val handle = "conversation-fixture-a"
        val route = AppRoute.MessageThread(ConversationHandle.fromOpaque(handle))

        assertFalse(route.toString().contains(handle))
        assertEquals("MessageThread", route.toString())
    }

    @Test
    fun settingsConfigurationHasStableTitleAndRestoresInsideSettingsRoot() {
        val nested = AppNavigator.push(AppNavigationState.initial(AppRoute.Settings), AppRoute.SettingsConfiguration)
        val restored = assertIs<NavigationRestoreResult.Success>(
            AppNavigationState.restore(nested.save(), AppNavigationState.initial()),
        )

        assertEquals("SIM 与网络配置", AppRoute.SettingsConfiguration.topBarTitle)
        assertEquals(nested, restored.state)
    }
}
