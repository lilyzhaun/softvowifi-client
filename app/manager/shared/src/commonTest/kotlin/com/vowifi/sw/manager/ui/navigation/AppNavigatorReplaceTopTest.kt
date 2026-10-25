package com.vowifi.sw.manager.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppNavigatorReplaceTopTest {
    private val thread = AppRoute.MessageThread(ConversationHandle.fromOpaque("conversation-synthetic-replacement"))

    @Test fun acceptedNewMessageReplacesOnlyItsTopEntryAndBackReturnsToMessages() {
        val root = AppNavigationState.initial(AppRoute.Messages)
        val composer = AppNavigator.push(root, AppRoute.NewMessage)
        val opened = AppNavigator.replaceTop(composer, thread)
        assertEquals(listOf(AppRoute.Messages, thread), opened.backStack)
        assertEquals(root, AppNavigator.back(opened))
        assertSame(opened, AppNavigator.replaceTop(opened, thread))
    }

    @Test fun replacementKeepsAnInternalSourceRootButRejectsRootsAndOtherBranches() {
        val home = AppNavigationState.initial(AppRoute.Home)
        assertSame(home, AppNavigator.replaceTop(home, thread))
        val composer = AppNavigator.open(home, AppRoute.NewMessage)
        assertEquals(listOf(AppRoute.Home, thread), AppNavigator.replaceTop(composer, thread).backStack)
        assertSame(composer, AppNavigator.replaceTop(composer, AppRoute.SettingsAbout))
        val stacked = AppNavigator.open(AppNavigator.open(home, thread), AppRoute.NewMessage)
        assertSame(stacked, AppNavigator.replaceTop(stacked, thread), "不能创建重复路由破坏原栈合同")
    }
}
