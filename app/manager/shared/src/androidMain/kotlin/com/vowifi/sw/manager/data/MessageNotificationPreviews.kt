package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal class MessageNotificationPreviews(
    private val lock: Any,
    private val update: (ConversationHandle, Int) -> Unit,
) {
    private class Entry(
        val conversation: StoredMessageConversation,
        val ids: Set<String>,
        var presentation: MessageNotificationPresentation?,
        var job: Job? = null,
    )
    private val entries = linkedMapOf<ConversationHandle, Entry>()
    private var scope: CoroutineScope? = null
    private var lookup: (suspend (StoredMessageConversation) -> List<MessageRecord>)? = null

    fun bind(scope: CoroutineScope, lookup: suspend (StoredMessageConversation) -> List<MessageRecord>) {
        this.scope = scope
        this.lookup = lookup
    }

    fun clear() {
        entries.values.forEach { it.job?.cancel() }
        entries.clear()
        scope = null
        lookup = null
    }

    fun presentation(handle: ConversationHandle): MessageNotificationPresentation? = entries[handle]?.presentation
    fun count(handle: ConversationHandle): Int = entries[handle]?.ids?.size ?: 0

    fun snapshot(owner: SimOwnerId, records: List<MessageRecord>, freshId: String?): Set<ConversationHandle> {
        val groups = records.filterIsInstance<MessageRecord.Incoming>().filter { !it.isRead }
            .groupBy { PeerNumberNormalizer.normalize(it.peerLabel) }
        val handles = groups.keys.map { messageConversationHandle(owner, it) }.toSet()
        entries.filter { it.value.conversation.owner == owner && it.key !in handles }.keys.toList().forEach(::remove)
        return groups.entries.sortedBy { (_, rows) -> rows.any { it.id == freshId } }.mapNotNull { (key, rows) ->
            val conversation = StoredMessageConversation(owner, key)
            val previous = entries[conversation.handle]
            put(conversation, rows.map { it.id }.toSet(), rows)
            conversation.handle.takeIf { previous?.ids == rows.map { it.id }.toSet() &&
                previous.presentation?.messages != entries[it]?.presentation?.messages }
        }.toSet()
    }

    fun thread(conversation: StoredMessageConversation, records: List<MessageRecord>): Boolean {
        val matching = records.filterIsInstance<MessageRecord.Incoming>().filter {
            !it.isRead && PeerNumberNormalizer.normalize(it.peerLabel) == conversation.key
        }
        val previous = entries[conversation.handle]
        val ids = matching.map { it.id }.toSet()
        put(conversation, ids, matching)
        return previous?.ids == ids && previous.presentation?.messages != entries[conversation.handle]?.presentation?.messages
    }

    fun unread(conversation: StoredMessageConversation, ids: Set<String>): Job? {
        val previous = entries[conversation.handle]
        val records = previous?.presentation?.messages.orEmpty().filter { it.id in ids }
        put(conversation, ids, records)
        val entry = entries[conversation.handle] ?: return null
        val read = lookup ?: return null
        entry.job = scope?.launch(start = CoroutineStart.LAZY) {
            val rows = try { read(conversation) } catch (_: MessageStorageException) { return@launch }
            synchronized(lock) {
                if (entries[conversation.handle] !== entry) return@synchronized
                entry.presentation = MessageNotificationPresentation.from(conversation, rows.filter { it.id in entry.ids })
                update(conversation.handle, ids.size)
            }
        }
        return entry.job
    }

    fun invalidateLookups(handles: Set<ConversationHandle>) {
        handles.mapNotNull(entries::get).forEach { entry ->
            entry.job?.cancel()
            entries[entry.conversation.handle] = Entry(entry.conversation, entry.ids, entry.presentation)
        }
    }

    fun read(owner: SimOwnerId, ids: Set<String>): List<Job> =
        entries.values.filter { it.conversation.owner == owner && it.ids.any { id -> id in ids } }.toList()
            .mapNotNull { unread(it.conversation, it.ids - ids) }

    fun remove(handle: ConversationHandle) { entries.remove(handle)?.job?.cancel() }

    private fun put(conversation: StoredMessageConversation, ids: Set<String>, records: List<MessageRecord>) {
        remove(conversation.handle)
        if (ids.isEmpty()) return
        entries[conversation.handle] = Entry(conversation, ids.toSet(), MessageNotificationPresentation.from(conversation, records))
        if (entries.size > 32) remove(entries.keys.first())
    }
}
