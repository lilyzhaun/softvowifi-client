package com.vowifi.sw.manager.ui.navigation

import kotlin.test.*

class CallMessageNavigationTest {
    @Test fun contextualSmsPushAndReplacementRestoreAndReturnToTheExactCallDetail() {
        val detail = AppRoute.CallHistoryDetail(CallHistoryHandle.fromOpaque("call-history-" + "a".repeat(43)))
        val initial = AppNavigator.push(AppNavigationState.initial(AppRoute.Calls), detail)
        val thread = AppRoute.MessageThread(ConversationHandle.parse("conversation-synthetic")!!)
        for (target in listOf(AppRoute.NewMessage, thread)) {
            val pushed = AppNavigator.openCallMessage(initial, target)
            assertEquals(listOf(AppRoute.Calls, detail, target), pushed.backStack)
            assertEquals(initial, AppNavigator.back(pushed))
            assertEquals(pushed, assertIs<NavigationRestoreResult.Success>(AppNavigationState.restore(pushed.save(), initial)).state)
            assertEquals(initial, AppNavigator.back(AppNavigator.replaceTop(pushed, thread)))
        }
        assertSame(initial, AppNavigator.openCallMessage(initial, AppRoute.SettingsAbout))
        assertEquals(AppNavigationState.initial(AppRoute.Calls), AppNavigator.back(initial))
    }
}
