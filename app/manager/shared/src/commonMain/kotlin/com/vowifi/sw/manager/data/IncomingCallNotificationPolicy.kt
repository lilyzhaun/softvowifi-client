package com.vowifi.sw.manager.data

enum class NotificationPolicyDecision {
    IN_APP_CALLS,
    HEADS_UP,
    HEADS_UP_FSI_DENIED,
    FULL_SCREEN,
    PERMISSION_DENIED,
}

data class NotificationPolicyInput(
    val appForeground: Boolean,
    val screenLocked: Boolean,
    val notificationsGranted: Boolean,
    val canUseFullScreen: Boolean,
)

fun evaluateNotificationPolicy(input: NotificationPolicyInput): NotificationPolicyDecision {
    if (input.appForeground) {
        return NotificationPolicyDecision.IN_APP_CALLS
    }
    if (!input.notificationsGranted) {
        return NotificationPolicyDecision.PERMISSION_DENIED
    }
    if (input.screenLocked && input.canUseFullScreen) {
        return NotificationPolicyDecision.FULL_SCREEN
    }
    if (input.screenLocked && !input.canUseFullScreen) {
        return NotificationPolicyDecision.HEADS_UP_FSI_DENIED
    }
    return NotificationPolicyDecision.HEADS_UP
}

fun shouldPostIncomingCallNotification(policy: NotificationPolicyDecision): Boolean = when (policy) {
    NotificationPolicyDecision.HEADS_UP,
    NotificationPolicyDecision.HEADS_UP_FSI_DENIED,
    NotificationPolicyDecision.FULL_SCREEN,
    -> true
    NotificationPolicyDecision.IN_APP_CALLS,
    NotificationPolicyDecision.PERMISSION_DENIED,
    -> false
}

fun shouldRequestIncomingCallNotificationPermission(
    runtimePermissionRequired: Boolean,
    permissionGranted: Boolean,
    requestRecorded: Boolean,
): Boolean = runtimePermissionRequired && !permissionGranted && !requestRecorded
