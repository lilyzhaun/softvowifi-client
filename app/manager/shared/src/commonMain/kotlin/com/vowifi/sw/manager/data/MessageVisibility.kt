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
        // A manual conversation reminder may be anchored to a sent or already-read message.
        // The repository still updates incoming read flags only, scoped to these visible IDs.
        val visibleIds = group.records.filter { it.id in observed }.map { it.id }
        if (visibleIds.isEmpty()) return@synchronized
        ObservedIncomingReadRequest.create(owner, group.conversationKey, visibleIds)?.let(onRead)
    }
}
