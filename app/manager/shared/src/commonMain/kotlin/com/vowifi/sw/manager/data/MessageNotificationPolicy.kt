package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.ui.navigation.ConversationHandle

class MessageNotificationPolicy(
    private val post: (ConversationHandle, Int, Boolean) -> Unit,
    private val cancel: (ConversationHandle) -> Unit,
    private val isActive: (ConversationHandle) -> Boolean = { true },
) {
    private val lock = Any()
    private val unread = mutableMapOf<SimOwnerId, Map<ConversationHandle, Set<String>>>()
    private var visible: ConversationHandle? = null
    private val posted = mutableSetOf<ConversationHandle>()

    fun visible(handle: ConversationHandle?) = synchronized(lock) {
        visible = handle
    }

    fun snapshot(owner: SimOwnerId, records: List<MessageRecord>, freshId: String? = null) = synchronized(lock) {
        val previous = unread[owner].orEmpty()
        val current = records.filterIsInstance<MessageRecord.Incoming>().filter { !it.isRead }
            .groupBy { messageConversationHandle(owner, PeerNumberNormalizer.normalize(it.peerLabel)) }
            .mapValues { (_, rows) -> rows.map { it.id }.toSet() }
        unread[owner] = current
        previous.keys.filter { it !in current }.forEach(::remove)
        current.forEach { (handle, ids) ->
            val fresh = freshId != null && freshId in ids
            when {
                fresh && handle != visible -> emit(handle, ids.size, true)
                previous[handle] != ids && handle in posted && isActive(handle) -> emit(handle, ids.size, false)
            }
        }
    }

    fun reconcile() = synchronized(lock) {
        unread.values.forEach { conversations ->
            conversations.forEach { (handle, ids) ->
                if (handle != visible) emit(handle, ids.size, false)
            }
        }
    }

    fun threadSnapshot(conversation: StoredMessageConversation, records: List<MessageRecord>) = synchronized(lock) {
        val ids = records.filterIsInstance<MessageRecord.Incoming>()
            .filter { !it.isRead && PeerNumberNormalizer.normalize(it.peerLabel) == conversation.key }
            .map { it.id }.toSet()
        threadUnread(conversation, ids)
    }

    fun threadUnread(conversation: StoredMessageConversation, ids: Set<String>) = synchronized(lock) {
        val handle = conversation.handle
        val previous = unread[conversation.owner].orEmpty()
        if (ids.isEmpty()) {
            unread[conversation.owner] = previous - handle
            remove(handle)
        } else {
            unread[conversation.owner] = previous + (handle to ids)
            if (previous[handle] != ids && handle in posted && isActive(handle)) emit(handle, ids.size, false)
        }
    }

    fun read(owner: SimOwnerId, ids: Set<String>) = synchronized(lock) {
        val previous = unread[owner] ?: return@synchronized
        val current = previous.mapValues { (_, unreadIds) -> unreadIds - ids }.filterValues { it.isNotEmpty() }
        unread[owner] = current
        previous.forEach { (handle, before) ->
            val after = current[handle]
            when {
                after == null -> remove(handle)
                after != before && handle in posted && isActive(handle) -> emit(handle, after.size, false)
            }
        }
    }

    private fun emit(handle: ConversationHandle, count: Int, alert: Boolean) {
        post(handle, count, alert)
        posted += handle
    }

    private fun remove(handle: ConversationHandle) {
        posted -= handle
        cancel(handle)
    }
}

internal fun messageConversationHandle(owner: SimOwnerId, key: String): ConversationHandle =
    ConversationHandle.fromOpaque("conversation-" + messageRouteDigest(owner.wire() + "\u0000" + key))

internal expect fun messageRouteDigest(value: String): String

fun interface MessagePersistenceObserver {
    fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?)
    fun onThreadSnapshot(conversation: StoredMessageConversation, records: List<MessageRecord>) = Unit
    fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) = Unit
    fun onRead(owner: SimOwnerId, ids: Set<String>) = Unit
}
