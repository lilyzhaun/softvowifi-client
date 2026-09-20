package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageUnreadSummary

internal fun fixtureUnreadSummary(state: MessageUiState): MessageUnreadSummary = when (state) {
    MessageUiState.Loading -> MessageUnreadSummary.Loading
    MessageUiState.Empty -> MessageUnreadSummary.Ready(0)
    is MessageUiState.Ready -> if (state.window != null) MessageUnreadSummary.Unavailable(null) else MessageUnreadSummary.Ready(
        state.records.filterIsInstance<MessageRecord.Incoming>().filter { !it.isRead }.map { it.id }.toSet().size.toLong(),
    )
    is MessageUiState.Inbox,
    is MessageUiState.Failure,
    is MessageUiState.PermissionDenied,
    is MessageUiState.Unavailable -> MessageUnreadSummary.Unavailable(null)
}
