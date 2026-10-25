package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus

internal class FakeMessageRepository : MessageRepository {
    private val labels = FakeMessageOwnerLabels()
    var failRemark = false
    override suspend fun ownerLabels(owners: List<SimOwnerId>) = labels.load(owners)
    override suspend fun saveOwnerRemark(owner: SimOwnerId, remark: MessageOwnerRemark): MessageOwnerLabel {
        if (failRemark) throw MessageStorageException()
        return labels.save(owner, remark)
    }
    var failNextInsert = false
    var failNextBind = false
    var failNextList = false
    var failOnListCall: Int? = null
    var failNextMarkUnknown = false
    var failNextIncoming = false
    var failNextMarkRead = false
    var failNextUnreadCount = false
    var insertQueuedCalls = 0
    var bindAcceptedCalls = 0
    var listByOwnerCalls = 0
    var markUnknownCalls = 0
    var closed = false

    private val messages = linkedMapOf<String, FakeStoredMessage>()
    override val history = FakeMessageHistoryRepository { messages.values.toList() }
    private var lastOwnerValue: SimOwnerId? = null
    private var probe: ByteArray? = null

    override suspend fun changeConversation(identity: StoredMessageConversation, action: MessageConversationAction): Boolean {
        checkOpen()
        val rows = messages.values.filter { it.ownerId == identity.owner && it.conversationKey == identity.key }
        if (rows.isEmpty()) return false
        when (action) {
            MessageConversationAction.MARK_UNREAD -> if (rows.none { it.incoming && !it.isRead }) {
                val anchor = rows.maxWith(compareBy<FakeStoredMessage> { it.messageAtMs }.thenBy { it.id }).id
                rows.forEach { messages[it.id] = it.copy(manualUnread = it.id == anchor) }
            }
            MessageConversationAction.MARK_READ -> {
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                rows.forEach { messages[it.id] = it.copy(manualUnread = false,
                    isRead = if (it.incoming) true else it.isRead,
                    readAtMs = if (it.incoming && !it.isRead) now else it.readAtMs) }
            }
            MessageConversationAction.DELETE -> rows.forEach { messages.remove(it.id) }
        }
        return true
    }

    override suspend fun listConversations(): List<StoredMessageConversation> = messages.values.map {
        StoredMessageConversation(it.ownerId, it.conversationKey)
    }.distinct()

    override suspend fun insertQueued(
        ownerId: SimOwnerId,
        messageId: String,
        peerAddress: String,
        body: String,
        nowMs: Long,
    ) {
        checkOpen()
        insertQueuedCalls += 1
        if (failNextInsert) {
            failNextInsert = false
            throw MessageStorageException()
        }
        messages[messageId] = FakeStoredMessage(
            id = messageId,
            ownerId = ownerId,
            incoming = false,
            peerAddress = peerAddress,
            conversationKey = PeerNumberNormalizer.normalize(peerAddress),
            body = body,
            messageAtMs = nowMs,
            engineSmsId = null,
            outgoingStatus = OutgoingMessageStatus.QUEUED,
            engineStatus = null,
            statusUpdatedAtMs = nowMs,
            failureReason = null,
            isRead = true,
            readAtMs = null,
        )
    }

    override suspend fun bindAccepted(
        ownerId: SimOwnerId,
        messageId: String,
        engineSmsId: String,
        nowMs: Long,
    ) {
        checkOpen()
        bindAcceptedCalls += 1
        if (failNextBind) {
            failNextBind = false
            throw MessageStorageException()
        }
        val current = messages[messageId] ?: return
        if (current.ownerId != ownerId || current.incoming) return
        if (current.outgoingStatus != OutgoingMessageStatus.QUEUED) return
        messages[messageId] = current.copy(
            engineSmsId = engineSmsId,
            outgoingStatus = OutgoingMessageStatus.ACCEPTED,
            statusUpdatedAtMs = nowMs,
        )
    }

    override suspend fun applyOutgoingStatus(
        ownerId: SimOwnerId,
        engineSmsId: String,
        status: SmsEngineStatus,
        nowMs: Long,
    ) {
        checkOpen()
        val current = messages.values.firstOrNull { stored ->
            stored.ownerId == ownerId && !stored.incoming && stored.engineSmsId == engineSmsId
        } ?: return
        val next = SmsStatusMachine.reduce(current.engineStatus, status)
        val nextUi = next.toUiStatus()
        if (next == current.engineStatus && nextUi == current.outgoingStatus) return
        messages[current.id] = current.copy(
            engineStatus = next,
            outgoingStatus = nextUi,
            statusUpdatedAtMs = nowMs,
        )
    }

    override suspend fun insertIncomingIfAbsent(
        ownerId: SimOwnerId,
        messageId: String,
        engineSmsId: String,
        peerAddress: String,
        body: String,
        messageAtMs: Long,
        nowMs: Long,
    ): IncomingInsertResult {
        checkOpen()
        if (failNextIncoming) {
            failNextIncoming = false
            throw MessageStorageException()
        }
        messages[messageId]?.let { existing ->
            return IncomingInsertResult(inserted = false, messageId = existing.id)
        }
        val duplicate = messages.values.firstOrNull { stored ->
            stored.ownerId == ownerId && stored.incoming && stored.engineSmsId == engineSmsId
        }
        if (duplicate != null) {
            return IncomingInsertResult(inserted = false, messageId = duplicate.id)
        }
        messages[messageId] = FakeStoredMessage(
            id = messageId,
            ownerId = ownerId,
            incoming = true,
            peerAddress = peerAddress,
            conversationKey = PeerNumberNormalizer.normalize(peerAddress),
            body = body,
            messageAtMs = messageAtMs,
            engineSmsId = engineSmsId,
            outgoingStatus = null,
            engineStatus = null,
            statusUpdatedAtMs = nowMs,
            failureReason = null,
            isRead = false,
            readAtMs = null,
        )
        return IncomingInsertResult(inserted = true, messageId = messageId)
    }

    override suspend fun markIncomingRead(
        request: ObservedIncomingReadRequest,
        nowMs: Long,
    ): IncomingReadUpdate {
        checkOpen()
        if (failNextMarkRead) {
            failNextMarkRead = false
            throw MessageStorageException()
        }
        if (request.observedMessageIds.isEmpty()) return IncomingReadUpdate.of(emptyList())
        val updated = ArrayList<String>()
        var clearedManual = false
        request.observedMessageIds.forEach { id ->
            val current = messages[id] ?: return@forEach
            if (current.ownerId != request.ownerId) return@forEach
            if (current.conversationKey != request.conversationKey) return@forEach
            if (current.manualUnread) { messages[id] = current.copy(manualUnread = false); clearedManual = true }
            if (!current.incoming || current.isRead) return@forEach
            messages[id] = current.copy(isRead = true, readAtMs = nowMs, manualUnread = false)
            updated += id
        }
        return IncomingReadUpdate.of(updated, clearedManual)
    }

    override suspend fun markFailed(ownerId: SimOwnerId, messageId: String, nowMs: Long) {
        checkOpen()
        val current = messages[messageId] ?: return
        if (current.ownerId != ownerId || current.incoming) return
        if (current.outgoingStatus != OutgoingMessageStatus.QUEUED) return
        messages[messageId] = current.copy(
            outgoingStatus = OutgoingMessageStatus.FAILED,
            engineStatus = SmsEngineStatus.FAILED,
            statusUpdatedAtMs = nowMs,
            failureReason = "protocol",
        )
    }

    override suspend fun markUnknown(ownerId: SimOwnerId, messageId: String, nowMs: Long) {
        checkOpen()
        markUnknownCalls += 1
        if (failNextMarkUnknown) {
            failNextMarkUnknown = false
            throw MessageStorageException()
        }
        val current = messages[messageId] ?: return
        if (current.ownerId != ownerId || current.incoming) return
        if (current.outgoingStatus != OutgoingMessageStatus.QUEUED) return
        messages[messageId] = current.copy(
            outgoingStatus = OutgoingMessageStatus.UNKNOWN,
            statusUpdatedAtMs = nowMs,
            failureReason = "unknown",
        )
    }

    override suspend fun interruptLeftoverQueued(ownerId: SimOwnerId, nowMs: Long) {
        checkOpen()
        messages.replaceAll { _, stored ->
            if (stored.ownerId == ownerId && !stored.incoming && stored.outgoingStatus == OutgoingMessageStatus.QUEUED) {
                stored.copy(
                    outgoingStatus = OutgoingMessageStatus.UNKNOWN,
                    statusUpdatedAtMs = nowMs,
                    failureReason = "interrupted",
                )
            } else {
                stored
            }
        }
    }

    override suspend fun listByOwner(ownerId: SimOwnerId): List<MessageRecord> {
        checkOpen()
        listByOwnerCalls += 1
        if (failNextList || failOnListCall == listByOwnerCalls) {
            failNextList = false
            throw MessageStorageException()
        }
        return messages.values
            .filter { stored -> stored.ownerId == ownerId }
            .sortedWith(compareBy({ stored -> stored.messageAtMs }, { stored -> stored.id }))
            .map(FakeStoredMessage::toRecord)
    }

    override suspend fun countUnreadIncoming(): Long = countUnreadByOwner().values.sum()

    override suspend fun countUnreadByOwner(): Map<SimOwnerId, Long> {
        checkOpen()
        if (failNextUnreadCount) {
            failNextUnreadCount = false
            throw MessageStorageException()
        }
        return messages.values.filter { it.countsAsUnread }.groupingBy { it.ownerId }.eachCount().mapValues { it.value.toLong() }
    }

    override suspend fun lastOwner(): SimOwnerId? {
        checkOpen()
        return lastOwnerValue
    }

    override suspend fun setLastOwner(ownerId: SimOwnerId) {
        checkOpen()
        lastOwnerValue = ownerId
    }

    override suspend fun readHmacProbe(): ByteArray? {
        checkOpen()
        return probe?.copyOf()
    }

    override suspend fun writeHmacProbe(probe: ByteArray) {
        checkOpen()
        this.probe = probe.copyOf()
    }

    override fun close() {
        closed = true
    }

    internal fun stored(messageId: String): FakeStoredMessage? = messages[messageId]

    private fun checkOpen() {
        check(!closed) { "repository closed" }
    }

}
