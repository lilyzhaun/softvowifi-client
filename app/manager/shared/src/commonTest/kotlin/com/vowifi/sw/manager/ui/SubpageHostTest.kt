package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubpageHostTest {
    @Test
    fun subpageHostIsComposedAcrossRootAndSubpageAndUsesKsuMiuixDefaultMotion() {
        val contract = subpageHostCompositionContract()

        assertTrue(contract.composedOnRoot)
        assertTrue(contract.composedOnSubpage)
        assertEquals(AppShellTransitionPolicy.None, contract.rootTransitionPolicy)
        assertEquals(AppShellTransitionPolicy.SlideOpaque, contract.subpageTransitionPolicy)
        assertEquals(SubpageMotionSource.KERNELSU_MIUUX_NAVDISPLAY_DEFAULT, contract.motionSource)
        assertFalse(contract.customTransitionOverrides)
        assertTrue(contract.predictivePopUsesDedicatedTransform)
        assertFalse(contract.predictivePopSharesRegularPopTransform)
        val homeKey = appRouteContentKey(AppRoute.Home)
        val settingsKey = appRouteContentKey(AppRoute.Settings)
        val appearanceKey = appRouteContentKey(AppRoute.SettingsAppearance)
        assertEquals(
            AppShellTransitionPolicy.None,
            subpageHostTransitionPolicy(homeKey, settingsKey, pop = false),
        )
        assertEquals(
            AppShellTransitionPolicy.SlideOpaque,
            subpageHostTransitionPolicy(settingsKey, appearanceKey, pop = false),
        )
        assertEquals(
            AppShellTransitionPolicy.SlideOpaque,
            subpageHostTransitionPolicy(appearanceKey, settingsKey, pop = true),
        )
        assertEquals(AppShellTransitionPolicy.None, subpageHostTransitionPolicy("unexpected", settingsKey, pop = true))
        assertEquals(4, contract.rootEntryCount)
        assertEquals(AppRoute.rootRoutes.size, contract.rootEntryCount)
        assertTrue(contract.rootEntriesPassThrough)
        assertFalse(contract.rootEntriesOpaque)
        assertEquals(subpageHostNavDisplayTransitionEffectsContract(), contract.navDisplayTransitionEffects)
    }

    @Test
    fun subpageHostNavDisplayTransitionEffectsContractSuppliesCustomZeroDimNonClippingEffects() {
        val contract = subpageHostNavDisplayTransitionEffectsContract()

        assertFalse(contract.cornerClip)
        assertEquals(0f, contract.dimAmount)
        assertTrue(contract.inputBlocking)
        assertFalse(contract.isDefaultEffects)
        assertFalse(contract.isNoneEffects)
        assertTrue(contract.sharedBetweenPredictiveOnAndOff)
    }

    @Test
    fun rootPolicyRetainsRootPagerAndNoneWhileSubpageHostMaintainsKsuMiuixDefaultMotion() {
        val rootState = AppNavigationState.initial(AppRoute.Settings)
        val subpageState = AppNavigator.push(rootState, AppRoute.SettingsAppearance)
        val rootPolicy = AppShellPolicy.forState(rootState)
        val subpagePolicy = AppShellPolicy.forState(subpageState)
        val hostContract = subpageHostCompositionContract()

        assertEquals(AppShellContentHost.RootPager, rootPolicy.contentHost)
        assertEquals(AppShellTransitionPolicy.None, rootPolicy.transition)

        assertEquals(AppShellContentHost.NavDisplay, subpagePolicy.contentHost)
        assertEquals(AppShellTransitionPolicy.SlideOpaque, subpagePolicy.transition)

        assertEquals(AppShellTransitionPolicy.SlideOpaque, hostContract.subpageTransitionPolicy)
        assertEquals(SubpageMotionSource.KERNELSU_MIUUX_NAVDISPLAY_DEFAULT, hostContract.motionSource)
        assertFalse(hostContract.customTransitionOverrides)
    }

    @Test
    fun contentKeysAreBundleSafeClassifiedAndUniquePerRouteInstance() {
        val threadA = AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-fixture-a"))
        val threadB = AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-fixture-b"))
        val routes = AppRoute.rootRoutes + listOf(
            threadA,
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
            val key = appRouteContentKey(route)
            assertTrue(key.startsWith(if (route is AppRoute.Root) "root:" else "subpage:"))
            assertEquals(key, appRouteContentKey(route))
        }
        assertFalse(appRouteContentKey(threadA) == appRouteContentKey(threadB))
    }
}
