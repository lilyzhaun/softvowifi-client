package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.ConversationHandle

class MessageVisibility(
    private val onVisible: (ConversationHandle?) -> Unit,
    private val onRead: (ObservedIncomingReadRequest) -> Unit,
) {
    private val lock = Any()
    private var resumed = false

    fun setResumed(value: Boolean) = synchronized(lock) {
        resumed = value
        if (!value) onVisible(null)
    }

    fun observe(state: MessageUiState, handle: ConversationHandle?, ids: List<String>) = synchronized(lock) {
        if (!resumed || handle == null) {
            onVisible(null)
            return@synchronized
        }
        val ready = state as? MessageUiState.Ready ?: return@synchronized
        val owner = ready.ownerId ?: return@synchronized
        val group = ConversationGrouping.group(ready.records).singleOrNull {
            messageConversationHandle(owner, it.conversationKey) == handle
        } ?: return@synchronized
        onVisible(handle)
        val observed = ids.toSet()
        val incoming = group.records.filterIsInstance<MessageRecord.Incoming>()
            .filter { !it.isRead && it.id in observed }.map { it.id }
        if (incoming.isEmpty()) return@synchronized
        ObservedIncomingReadRequest.create(owner, group.conversationKey, incoming)?.let(onRead)
    }
}
