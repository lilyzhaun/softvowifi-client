package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.ui.privateUiPhoneDisplay

data class IncomingCallNotificationState(
    val notificationKey: String?,
    val peerName: String?,
) {
    val isActive: Boolean get() = notificationKey != null
    override fun toString(): String = "IncomingCallNotificationState(isActive=$isActive)"
}

sealed interface IncomingCallNotificationCommand {
    data class Post(val peerName: String, val notificationKey: String) : IncomingCallNotificationCommand {
        override fun toString(): String = "IncomingCallNotificationCommand.Post"
    }
    data object Cancel : IncomingCallNotificationCommand
    data object NoOp : IncomingCallNotificationCommand
}

sealed interface IncomingCallActionResult {
    data class Answer(val notificationKey: String) : IncomingCallActionResult
    data class Reject(val notificationKey: String) : IncomingCallActionResult
    data object Ignore : IncomingCallActionResult
}

fun computeNotificationCommand(
    current: IncomingCallNotificationState,
    phase: CallPhase,
    peerName: String?,
    keyGenerator: () -> String,
): Pair<IncomingCallNotificationState, IncomingCallNotificationCommand> {
    if (phase == CallPhase.Ringing) {
        if (current.isActive) {
            return Pair(current, IncomingCallNotificationCommand.NoOp)
        }
        val key = keyGenerator()
        val displayName = privateUiPhoneDisplay(peerName)
        return Pair(
            IncomingCallNotificationState(notificationKey = key, peerName = displayName),
            IncomingCallNotificationCommand.Post(peerName = displayName, notificationKey = key),
        )
    }
    if (current.isActive) {
        return Pair(
            IncomingCallNotificationState(notificationKey = null, peerName = null),
            IncomingCallNotificationCommand.Cancel,
        )
    }
    return Pair(current, IncomingCallNotificationCommand.NoOp)
}

fun resolveIncomingCallAction(
    state: IncomingCallNotificationState,
    notificationKey: String,
    action: IncomingCallAction,
): IncomingCallActionResult {
    if (state.notificationKey == null || state.notificationKey != notificationKey) {
        return IncomingCallActionResult.Ignore
    }
    return when (action) {
        IncomingCallAction.ANSWER -> IncomingCallActionResult.Answer(notificationKey)
        IncomingCallAction.REJECT -> IncomingCallActionResult.Reject(notificationKey)
    }
}

fun shouldHandleIncomingCallAnswer(
    actionName: String?,
    microphoneGranted: Boolean,
): Boolean = actionName == IncomingCallAction.ANSWER.actionName && microphoneGranted

enum class IncomingCallAction(val actionName: String) {
    ANSWER("com.vowifi.sw.action.ANSWER"),
    REJECT("com.vowifi.sw.action.REJECT"),
}

data class IncomingCallNotificationCallbacks(
    val onAnswer: () -> Unit,
    val onReject: () -> Unit,
)

const val UNKNOWN_CALLER_NAME = "未知号码"
