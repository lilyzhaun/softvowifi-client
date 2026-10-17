package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import com.vowifi.sw.manager.model.MessageSendAttempt
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.data.isStorageAttachmentNotice

private fun submittedMessageAttempt(snapshot: List<String>, state: MessageUiState): MessageSendAttempt? {
    val ready = state as? MessageUiState.Available ?: return null
    if (ready.ownerId?.wire().orEmpty() != snapshot[0]) return null
    val attempt = ready.sendAttempt ?: return null
    return attempt.takeIf {
        it.owner == ready.ownerId && it.messageId !in snapshot.drop(3) &&
            it.recipient == snapshot[1] && it.body == snapshot[2]
    }
}

internal fun submittedMessageOutcome(snapshot: List<String>, state: MessageUiState): OutgoingMessageStatus? =
    submittedMessageAttempt(snapshot, state)?.status

internal fun shouldClearSubmittedDraft(status: OutgoingMessageStatus?, submitted: String, current: String): Boolean =
    current == submitted && (status == OutgoingMessageStatus.ACCEPTED || status == OutgoingMessageStatus.DELIVERY_REPORTED)

internal class MessageDraftSubmission(
    val pending: Boolean,
    val outcome: OutgoingMessageStatus?,
    val draftEdited: () -> Unit,
    /** The persisted queued record of this still-pending submission; the draft itself is retained. */
    val handoffId: String? = null,
    val submit: () -> Unit,
)

private class MessageOutcomeFeedback(val attempt: MessageSendAttempt, val clearedBySubmission: Boolean) {
    fun matches(owner: SimOwnerId?, recipient: String, body: String): Boolean =
        owner == attempt.owner && (
            recipient == attempt.recipient && body == attempt.body ||
                clearedBySubmission && body.isEmpty() && (recipient == attempt.recipient || recipient.isEmpty())
            )
}

@Composable
internal fun rememberMessageDraftSubmission(
    state: MessageUiState,
    recipient: String,
    body: String,
    onAccepted: () -> Unit,
    onSend: (String, String) -> Unit,
): MessageDraftSubmission {
    var snapshot by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var observedBusy by rememberSaveable { mutableStateOf(false) }
    var submissionEdited by rememberSaveable { mutableStateOf(false) }
    val routeActive by rememberUpdatedState(LocalAppRouteActive.current)
    val ready = state as? MessageUiState.Available
    var feedback by remember(ready?.ownerId) { mutableStateOf<MessageOutcomeFeedback?>(null) }
    val visibleFeedback = feedback?.takeIf { it.matches(ready?.ownerId, recipient, body) }
    LaunchedEffect(feedback, ready?.ownerId, recipient, body) {
        if (visibleFeedback == null) feedback = null
    }
    LaunchedEffect(state, snapshot, recipient, body) {
        val submitted = snapshot ?: return@LaunchedEffect
        if (ready?.sendBusy == true) observedBusy = true
        if (recipient != submitted[1] || body != submitted[2]) submissionEdited = true
        val outcome = submittedMessageOutcome(submitted, state)
        if (outcome != null && outcome != OutgoingMessageStatus.QUEUED && !submissionEdited) {
            val clearDraft = shouldClearSubmittedDraft(outcome, submitted[2], body) && recipient == submitted[1]
            feedback = ready?.sendAttempt?.let { MessageOutcomeFeedback(it, clearDraft) }
            if (clearDraft) onAccepted()
        }
        if (outcome != null && outcome != OutgoingMessageStatus.QUEUED ||
            observedBusy && ready?.sendBusy != true || state is MessageUiState.Failure || state is MessageUiState.Unavailable ||
            state is MessageUiState.PermissionDenied ||
            ready != null && (ready.ownerId?.wire().orEmpty() != submitted[0] || !ready.composerEnabled || ready.attachmentNotice?.let(::isStorageAttachmentNotice) == true)
        ) {
            snapshot = null
            observedBusy = false
        }
    }
    return MessageDraftSubmission(
        pending = snapshot != null || ready?.sendBusy == true,
        outcome = visibleFeedback?.attempt?.status,
        draftEdited = { feedback = null; if (snapshot != null) submissionEdited = true },
        handoffId = snapshot?.takeIf { !submissionEdited }?.let { submittedMessageAttempt(it, state) }?.messageId,
    ) {
        if (routeActive && snapshot == null && canSendMessage(state, recipient, body) && ready != null) {
            feedback = null
            submissionEdited = false
            snapshot = listOf(ready.ownerId?.wire().orEmpty(), recipient, body) + listOfNotNull(ready.sendAttempt?.messageId)
            observedBusy = false
            onSend(recipient, body)
        }
    }
}

internal fun shouldFollowNewMessages(initial: Boolean, previousLastIndex: Int, lastVisibleIndex: Int): Boolean =
    initial || lastVisibleIndex >= previousLastIndex - 1
