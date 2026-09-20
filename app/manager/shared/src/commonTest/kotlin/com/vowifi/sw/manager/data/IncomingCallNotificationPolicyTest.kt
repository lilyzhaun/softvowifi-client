package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncomingCallNotificationPolicyTest {
    @Test
    fun foregroundReturnsInAppCalls() {
        val input = NotificationPolicyInput(
            appForeground = true,
            screenLocked = false,
            notificationsGranted = true,
            canUseFullScreen = true,
        )
        assertEquals(NotificationPolicyDecision.IN_APP_CALLS, evaluateNotificationPolicy(input))
    }

    @Test
    fun foregroundWithNotificationsDeniedStillReturnsInAppCalls() {
        val input = NotificationPolicyInput(
            appForeground = true,
            screenLocked = false,
            notificationsGranted = false,
            canUseFullScreen = true,
        )
        assertEquals(NotificationPolicyDecision.IN_APP_CALLS, evaluateNotificationPolicy(input))
    }

    @Test
    fun unlockedBackgroundReturnsHeadsUp() {
        val input = NotificationPolicyInput(
            appForeground = false,
            screenLocked = false,
            notificationsGranted = true,
            canUseFullScreen = true,
        )
        assertEquals(NotificationPolicyDecision.HEADS_UP, evaluateNotificationPolicy(input))
    }

    @Test
    fun lockedWithNotificationsAndFullScreenReturnsFullScreen() {
        val input = NotificationPolicyInput(
            appForeground = false,
            screenLocked = true,
            notificationsGranted = true,
            canUseFullScreen = true,
        )
        assertEquals(NotificationPolicyDecision.FULL_SCREEN, evaluateNotificationPolicy(input))
    }

    @Test
    fun lockedWithNotificationsWithoutFullScreenReturnsHeadsUpWithRecovery() {
        val input = NotificationPolicyInput(
            appForeground = false,
            screenLocked = true,
            notificationsGranted = true,
            canUseFullScreen = false,
        )
        assertEquals(NotificationPolicyDecision.HEADS_UP_FSI_DENIED, evaluateNotificationPolicy(input))
    }

    @Test
    fun notificationsDeniedWhileBackgroundReturnsPermissionDenied() {
        val input = NotificationPolicyInput(
            appForeground = false,
            screenLocked = false,
            notificationsGranted = false,
            canUseFullScreen = true,
        )
        assertEquals(NotificationPolicyDecision.PERMISSION_DENIED, evaluateNotificationPolicy(input))
    }

    @Test
    fun notificationsDeniedWhileLockedReturnsPermissionDenied() {
        val input = NotificationPolicyInput(
            appForeground = false,
            screenLocked = true,
            notificationsGranted = false,
            canUseFullScreen = true,
        )
        assertEquals(NotificationPolicyDecision.PERMISSION_DENIED, evaluateNotificationPolicy(input))
    }

    @Test
    fun unlockedBackgroundWithoutFullScreenStillReturnsHeadsUp() {
        val input = NotificationPolicyInput(
            appForeground = false,
            screenLocked = false,
            notificationsGranted = true,
            canUseFullScreen = false,
        )
        assertEquals(NotificationPolicyDecision.HEADS_UP, evaluateNotificationPolicy(input))
    }

    @Test
    fun foregroundAndPermissionDeniedPoliciesDoNotPostNotifications() {
        assertFalse(shouldPostIncomingCallNotification(NotificationPolicyDecision.IN_APP_CALLS))
        assertFalse(shouldPostIncomingCallNotification(NotificationPolicyDecision.PERMISSION_DENIED))
    }

    @Test
    fun backgroundNotificationPoliciesPostNotifications() {
        assertTrue(shouldPostIncomingCallNotification(NotificationPolicyDecision.HEADS_UP))
        assertTrue(shouldPostIncomingCallNotification(NotificationPolicyDecision.HEADS_UP_FSI_DENIED))
        assertTrue(shouldPostIncomingCallNotification(NotificationPolicyDecision.FULL_SCREEN))
    }

    @Test
    fun runtimeNotificationPermissionIsRequestedOnlyOnceWhenRequired() {
        assertTrue(shouldRequestIncomingCallNotificationPermission(true, false, false))
        assertFalse(shouldRequestIncomingCallNotificationPermission(false, false, false))
        assertFalse(shouldRequestIncomingCallNotificationPermission(true, true, false))
        assertFalse(shouldRequestIncomingCallNotificationPermission(true, false, true))
    }
}
