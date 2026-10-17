package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSubpageSceneTest {
    @Test
    fun everySubpageUsesTheSameOpaqueSceneOwnership() {
        assertEquals("通知设置", AppRoute.SettingsCallNotifications.topBarTitle)
        // R7 supplement (2026-10-15): settings chrome matches the collapsed Home header.
        val foldedHomeAlpha = topBarBlendAlpha(1f)
        assertEquals(0.92f, foldedHomeAlpha)
        val routes = listOf(
            AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-test")) to 0.92f,
            AppRoute.NewMessage to 0.92f,
            AppRoute.SettingsAppearance to foldedHomeAlpha,
            AppRoute.SettingsConfiguration to foldedHomeAlpha,
            AppRoute.SettingsDiagnostics to foldedHomeAlpha,
            AppRoute.SettingsLogs to foldedHomeAlpha,
            AppRoute.SettingsCallNotifications to foldedHomeAlpha,
            AppRoute.SettingsAbout to foldedHomeAlpha,
        )

        routes.forEach { (route, expectedBlendAlpha) ->
            val scene = appSubpageSceneContract(route)
            assertEquals(route.topBarTitle, scene.title)
            assertEquals(1, scene.entrySceneCount)
            assertEquals(0, scene.globalSubpageTopBarCount)
            assertFalse(scene.rootPlaceholderOwnsContent)
            assertTrue(scene.pushPopMovesSceneAtomically)
            assertTrue(scene.sceneOwnsTopBar)
            assertTrue(scene.sceneOwnsContent)
            assertTrue(scene.opaqueContainer)
            assertFalse(scene.contentRootAppliesScaffoldPadding)
            assertTrue(scene.bodyDrawsFromOrigin)
            assertTrue(scene.topBarOverlaysBody)
            assertTrue(scene.listConsumesVerticalInsetViaContentPadding)
            assertTrue(scene.listConsumesBottomInsetViaContentPadding)
            assertEquals(expectedBlendAlpha, scene.topBarBlendAlpha, route.topBarTitle)
            assertEquals(AppPageWindowInsets.EMBEDDED_ZERO, appPageWindowInsets(showTopBar = false))
            assertFalse(appPageNestedScrollEnabled(showTopBar = false))
        }
    }

    @Test
    fun dialpadOwnsItsNativeHeaderAndSafeInsetsWithoutAnotherTopBar() {
        val scene = appSubpageSceneContract(AppRoute.Dialpad)
        assertEquals("拨号", scene.title)
        assertEquals(1, scene.entrySceneCount)
        assertEquals(0, scene.globalSubpageTopBarCount)
        assertTrue(scene.opaqueContainer)
        assertTrue(scene.sceneOwnsContent)
        assertFalse(scene.topBarOverlaysBody)
        assertFalse(scene.listConsumesVerticalInsetViaContentPadding)
        assertFalse(scene.listConsumesBottomInsetViaContentPadding)
        assertEquals(0f, scene.topBarBlendAlpha)
    }

    @Test
    fun onlyMessageThreadConsumesTheProjectedConversationTitle() {
        val thread = AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-test"))

        assertEquals("+155501", subpageTitleOverride(thread, "+155501"))
        assertEquals(null, subpageTitleOverride(AppRoute.NewMessage, "+155501"))
    }
}
