package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUnreadSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val COUNT_UNREAD_INCOMING_SQL =
    "SELECT COUNT(*) FROM messages WHERE direction = 'INCOMING' AND `read` = 0"

internal class MessageUnreadSummaryStore(repository: MessageRepository, scope: CoroutineScope) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow<MessageUnreadSummary>(MessageUnreadSummary.Loading)
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            var lastKnownTotal: Long? = null
            for (request in requests) {
                do {
                    val summary = try {
                        MessageUnreadSummary.Ready(repository.countUnreadIncoming())
                    } catch (_: MessageStorageException) {
                        MessageUnreadSummary.Unavailable(lastKnownTotal)
                    }
                    val superseded = requests.tryReceive().isSuccess
                    if (!superseded && isActive) {
                        lastKnownTotal = when (summary) {
                            is MessageUnreadSummary.Ready -> summary.total
                            is MessageUnreadSummary.Unavailable -> summary.lastKnownTotal
                            MessageUnreadSummary.Loading -> lastKnownTotal
                        }
                        mutableState.value = summary
                    }
                } while (superseded && isActive)
            }
        }.invokeOnCompletion { requests.close() }
    }

    fun refresh() {
        requests.trySend(Unit)
    }
}
