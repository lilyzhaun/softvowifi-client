package com.vowifi.sw.manager.ui.navigation

import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.AppShellContentHost
import com.vowifi.sw.manager.ui.AppShellEntrySurfacePolicy
import com.vowifi.sw.manager.ui.AppShellPolicy
import com.vowifi.sw.manager.ui.AppShellTransitionPolicy
import com.vowifi.sw.manager.ui.AppearanceSettings
import com.vowifi.sw.manager.ui.BottomChromeMode
import com.vowifi.sw.manager.ui.appChromeSpec
import com.vowifi.sw.manager.ui.bottomChromeTransitionContract
import com.vowifi.sw.manager.ui.directRootKey
import com.vowifi.sw.manager.ui.subpageTopBarContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppNavigationTest {
    @Test
    fun rootToSubpageAndBackPopsExactlyOne() {
        val settings = AppNavigator.selectRoot(AppNavigationState.initial(), AppRoute.Settings)
        val diagnostics = AppNavigator.push(settings, AppRoute.SettingsDiagnostics)

        assertEquals(AppRoute.SettingsDiagnostics, diagnostics.topRoute)
        assertFalse(diagnostics.bottomBarVisible)

        val restoredSettings = AppNavigator.back(diagnostics)

        assertEquals(listOf(AppRoute.Settings), restoredSettings.backStack)
        assertTrue(restoredSettings.bottomBarVisible)
    }

    @Test
    fun selectingCurrentRootAtRootAndBackingFromRootAreIdentityOperations() {
        val home = AppNavigationState.initial()

        assertSame(home, AppNavigator.selectRoot(home, AppRoute.Home))
        assertSame(home, AppNavigator.back(home))
    }

    @Test
    fun rootSwitchUsesPagerWithoutCrossfadeAndKeepsOneStableRootEntry() {
        var state = AppNavigationState.initial()

        AppRoute.rootRoutes.forEach { root ->
            val selected = AppNavigator.selectRoot(state, root)
            val repeated = AppNavigator.selectRoot(selected, root)

            val policy = AppShellPolicy.forState(selected)

            assertEquals(AppShellContentHost.RootPager, policy.contentHost)
            assertEquals(AppShellTransitionPolicy.None, policy.transition)
            assertEquals(AppShellEntrySurfacePolicy.OpaqueCanvas, policy.entrySurface)
            assertSame(root, directRootKey(selected))
            assertEquals(listOf(root), selected.backStack)
            assertSame(root, selected.backStack.single())
            assertEquals(1, selected.backStack.filterIsInstance<AppRoute.Root>().size)
            assertSame(selected, repeated)
            state = repeated
        }
    }

    @Test
    fun switchingRootClearsOnlyTheVisibleSubpage() {
        val thread = AppNavigator.push(
            AppNavigator.selectRoot(AppNavigationState.initial(), AppRoute.Messages),
            AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-fixture-a")),
        )

        val calls = AppNavigator.selectRoot(thread, AppRoute.Calls)
        val messages = AppNavigator.selectRoot(calls, AppRoute.Messages)

        assertEquals(listOf(AppRoute.Calls), calls.backStack)
        assertEquals(listOf(AppRoute.Messages), messages.backStack)
        assertTrue(messages.bottomBarVisible)
    }

    @Test
    fun saveAndRestorePreservesNestedRouteAndRoot() {
        val nested = AppNavigator.push(
            AppNavigator.selectRoot(AppNavigationState.initial(), AppRoute.Settings),
            AppRoute.SettingsCallNotifications,
        )

        val restored = assertIs<NavigationRestoreResult.Success>(
            AppNavigationState.restore(nested.save(), AppNavigationState.initial()),
        )

        assertEquals(nested, restored.state)
        assertEquals(AppRoute.Settings, restored.state.currentRoot)
        assertEquals(AppRoute.SettingsCallNotifications, restored.state.topRoute)
    }

    @Test
    fun malformedOrUnknownStateIsRejectedWithoutResettingCurrentState() {
        val current = AppNavigator.selectRoot(AppNavigationState.initial(), AppRoute.Calls)
        val malformed = listOf("not-json", "{\"backStack\":[{\"type\":\"Unknown\"}]}")

        malformed.forEach { serialized ->
            val failure = assertIs<NavigationRestoreResult.Failure>(
                AppNavigationState.restore(serialized, current),
            )

            assertSame(current, failure.state)
            assertEquals(NavigationRestoreError.MALFORMED_STATE, failure.error)
        }
    }

    @Test
    fun bottomBarTraceMatchesRootOnlyContract() {
        val home = AppNavigationState.initial()
        val settings = AppNavigator.selectRoot(home, AppRoute.Settings)
        val diagnostics = AppNavigator.push(settings, AppRoute.SettingsDiagnostics)
        val back = AppNavigator.back(diagnostics)

        assertEquals(
            listOf(true, true, false, true, true),
            listOf(
                home.bottomBarVisible,
                settings.bottomBarVisible,
                diagnostics.bottomBarVisible,
                back.bottomBarVisible,
                AppNavigator.selectRoot(back, AppRoute.Settings).bottomBarVisible,
            ),
        )
    }

    @Test
    fun hostPolicyHidesRootTopBarAndKeepsOpaqueBackOnlySubpageChrome() {
        AppRoute.rootRoutes.forEach { root ->
            val state = AppNavigationState.initial(root)

            assertEquals(
                AppShellPolicy(
                    showBottomBar = true,
                    showTopBar = false,
                    showBack = false,
                    contentHost = AppShellContentHost.RootPager,
                    transition = AppShellTransitionPolicy.None,
                    entrySurface = AppShellEntrySurfacePolicy.OpaqueCanvas,
                ),
                AppShellPolicy.forState(state),
            )
            assertSame(root, directRootKey(state))
        }

        val subpages = listOf(
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
        subpages.forEach { route ->
            val state = AppNavigator.push(AppNavigationState.initial(route.root), route)

            assertEquals(
                AppShellPolicy(
                    showBottomBar = false,
                    showTopBar = true,
                    showBack = true,
                    contentHost = AppShellContentHost.NavDisplay,
                    transition = AppShellTransitionPolicy.SlideOpaque,
                    entrySurface = AppShellEntrySurfacePolicy.OpaqueCanvas,
                ),
                AppShellPolicy.forState(state),
            )
            assertEquals(null, directRootKey(state))
        }
    }

    @Test
    fun everySubpageUsesOneLeading48DpBackAndNoTrailingBackAction() {
        val subpages = listOf(
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

        subpages.forEach { route ->
            val contract = subpageTopBarContract(route)
            assertTrue(contract.usesLeadingNavigationIcon)
            assertEquals(48, contract.backTargetDp)
            assertEquals(0, contract.trailingBackActionCount)
            assertTrue(contract.titleOpticallyConstrained)
        }
    }

    @Test
    fun chromeSpecSelectsFloatingNormalAndHonestBlurFallback() {
        val floating = appChromeSpec(
            AppearanceSettings(
                floatingNavigation = true,
                blurRequested = true,
                liquidGlassRequested = true,
                predictiveBackRequested = true,
                hapticsEnabled = false,
            ),
            blurSupported = true,
        )
        val normal = appChromeSpec(
            AppearanceSettings(
                floatingNavigation = false,
                blurRequested = true,
                liquidGlassRequested = true,
                predictiveBackRequested = false,
                hapticsEnabled = true,
            ),
            blurSupported = true,
        )
        val fallback = appChromeSpec(
            AppearanceSettings(
                floatingNavigation = true,
                blurRequested = true,
                liquidGlassRequested = true,
                predictiveBackRequested = false,
                hapticsEnabled = true,
            ),
            blurSupported = false,
        )

        assertEquals(BottomChromeMode.FLOATING, floating.bottomMode)
        assertTrue(floating.effectiveBlur)
        assertTrue(floating.predictiveBackEnabled)
        assertFalse(floating.hapticsEnabled)

        assertEquals(BottomChromeMode.NORMAL, normal.bottomMode)
        assertTrue(normal.effectiveBlur)
        assertFalse(normal.predictiveBackEnabled)
        assertTrue(normal.hapticsEnabled)

        assertEquals(BottomChromeMode.FLOATING, fallback.bottomMode)
        assertFalse(fallback.effectiveBlur)
        assertTrue(fallback.opaqueFallback)
        assertEquals(25f, floating.blurRadius)
        assertEquals(180, floating.motionDurationMillis)
        assertEquals(0.96f, floating.pressedScale)
        assertEquals(220, bottomChromeTransitionContract().modeDurationMillis)
        assertEquals(0, bottomChromeTransitionContract().selectedIndicatorDurationMillis)
        assertEquals(180, bottomChromeTransitionContract().selectedPressDurationMillis)
        assertTrue(bottomChromeTransitionContract().stableReservedInset)
    }

    @Test
    fun initialDestinationAndRepeatedIncomingRootsAreDeterministic() {
        var state = AppNavigationState.initial(AppDestination.SETTINGS.rootRoute)
        state = AppNavigator.push(state, AppRoute.SettingsDiagnostics)

        listOf(AppRoute.Calls, AppRoute.Calls).forEach { root ->
            state = AppNavigator.selectRoot(state, root)
        }

        assertEquals(listOf(AppRoute.Calls), state.backStack)
        assertTrue(state.bottomBarVisible)
    }

    @Test
    fun ringingTransitionClearsNestedRouteToCallsRoot() {
        val nested = AppNavigator.push(
            AppNavigationState.initial(AppRoute.Settings),
            AppRoute.SettingsDiagnostics,
        )

        val routed = AppNavigator.forCallTransition(nested, CallPhase.Idle, CallPhase.Ringing)
        val repeated = AppNavigator.forCallTransition(routed, CallPhase.Ringing, CallPhase.Ringing)

        assertEquals(listOf(AppRoute.Calls), routed.backStack)
        assertSame(routed, repeated)
    }

    @Test
    fun nonRingingCallTransitionsPreserveTheCurrentNavigationObject() {
        val current = AppNavigator.push(
            AppNavigationState.initial(AppRoute.Settings),
            AppRoute.SettingsDiagnostics,
        )

        assertSame(current, AppNavigator.forCallTransition(current, CallPhase.Idle, CallPhase.Dialing))
        assertSame(current, AppNavigator.forCallTransition(current, CallPhase.Ringing, CallPhase.Connecting))
        assertSame(current, AppNavigator.forCallTransition(current, CallPhase.InCall, CallPhase.Idle))
    }

}
