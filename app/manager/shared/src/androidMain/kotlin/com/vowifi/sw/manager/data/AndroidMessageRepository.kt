package com.vowifi.sw.manager.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class AndroidMessageRepository(
    private val database: SoftVoWiFiMessageDatabase,
) : MessageRepository {
    private val messages = database.messageDao()
    private val metadata = database.metadataDao()
    override val history: MessageHistoryRepository = AndroidMessageHistoryStore(database.historyDao())

    override suspend fun changeConversation(identity: StoredMessageConversation, action: MessageConversationAction): Boolean = storageCall {
        messages.changeConversation(identity.owner.wire(), identity.key, action)
    }

    override suspend fun unreadNotificationPreview(identity: StoredMessageConversation): List<MessageRecord> = storageCall {
        messages.unreadNotificationPreview(identity.owner.wire(), identity.key).asReversed().map(MessageEntity::toRecord)
    }

    override suspend fun ownerLabels(owners: List<SimOwnerId>): Map<SimOwnerId, MessageOwnerLabel> = storageCall {
        val labels = metadata.ownerLabels(owners.map { it.wire() })
        owners.associateWith { labels.getValue(it.wire()) }
    }

    override suspend fun saveOwnerRemark(owner: SimOwnerId, remark: MessageOwnerRemark): MessageOwnerLabel = storageCall {
        metadata.saveOwnerRemark(owner.wire(), remark.value)
    }

    override suspend fun insertQueued(
        ownerId: SimOwnerId,
        messageId: String,
        peerAddress: String,
        body: String,
        nowMs: Long,
    ) = storageCall {
        messages.insert(
            MessageEntity(
                messageId = messageId,
                simOwnerId = ownerId.wire(),
                direction = MESSAGE_DIRECTION_OUTGOING,
                peerAddress = peerAddress,
                conversationKey = PeerNumberNormalizer.normalize(peerAddress),
                body = body,
                messageAtMs = nowMs,
                engineSmsId = null,
                outgoingStatus = OutgoingMessageStatus.QUEUED.name,
                engineStatus = null,
                statusUpdatedAtMs = nowMs,
                failureReason = null,
            ),
        )
    }

    override suspend fun bindAccepted(
        ownerId: SimOwnerId,
        messageId: String,
        engineSmsId: String,
        nowMs: Long,
    ) = storageCall {
        messages.bindAccepted(
            messageId = messageId,
            ownerId = ownerId.wire(),
            outgoing = MESSAGE_DIRECTION_OUTGOING,
            engineSmsId = engineSmsId,
            status = OutgoingMessageStatus.ACCEPTED.name,
            queued = OutgoingMessageStatus.QUEUED.name,
            nowMs = nowMs,
        )
    }

    override suspend fun applyOutgoingStatus(
        ownerId: SimOwnerId,
        engineSmsId: String,
        status: SmsEngineStatus,
        nowMs: Long,
    ) = storageCall {
        messages.applyReportedStatus(
            ownerId = ownerId.wire(),
            direction = MESSAGE_DIRECTION_OUTGOING,
            engineSmsId = engineSmsId,
            reported = status,
            nowMs = nowMs,
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
    ): IncomingInsertResult = storageCall {
        val rowId = messages.insertOrIgnore(
            MessageEntity(
                messageId = messageId,
                simOwnerId = ownerId.wire(),
                direction = MESSAGE_DIRECTION_INCOMING,
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
            ),
        )
        if (rowId > 0L) return@storageCall IncomingInsertResult(inserted = true, messageId = messageId)
        val existing = messages.findById(messageId)
            ?: messages.findByEngineSmsId(ownerId.wire(), MESSAGE_DIRECTION_INCOMING, engineSmsId)
        IncomingInsertResult(inserted = false, messageId = existing?.messageId ?: messageId)
    }

    override suspend fun markIncomingRead(
        request: ObservedIncomingReadRequest,
        nowMs: Long,
    ): IncomingReadUpdate = storageCall {
        messages.markIncomingRead(
                ownerId = request.ownerId.wire(),
                conversationKey = request.conversationKey,
                observedIds = request.observedMessageIds,
                nowMs = nowMs,
        )
    }

    override suspend fun markFailed(ownerId: SimOwnerId, messageId: String, nowMs: Long) = storageCall {
        messages.markQueued(
            messageId = messageId,
            ownerId = ownerId.wire(),
            outgoing = MESSAGE_DIRECTION_OUTGOING,
            queued = OutgoingMessageStatus.QUEUED.name,
            outgoingStatus = OutgoingMessageStatus.FAILED.name,
            engineStatus = SmsEngineStatus.FAILED.name,
            nowMs = nowMs,
            failureReason = boundReason("protocol"),
        )
    }

    override suspend fun markUnknown(ownerId: SimOwnerId, messageId: String, nowMs: Long) = storageCall {
        messages.markQueuedUnknown(
            messageId = messageId,
            ownerId = ownerId.wire(),
            outgoing = MESSAGE_DIRECTION_OUTGOING,
            queued = OutgoingMessageStatus.QUEUED.name,
            outgoingStatus = OutgoingMessageStatus.UNKNOWN.name,
            nowMs = nowMs,
            failureReason = boundReason("unknown"),
        )
    }

    override suspend fun interruptLeftoverQueued(ownerId: SimOwnerId, nowMs: Long) = storageCall {
        messages.interruptQueued(
            ownerId = ownerId.wire(),
            unknown = OutgoingMessageStatus.UNKNOWN.name,
            outgoing = MESSAGE_DIRECTION_OUTGOING,
            queued = OutgoingMessageStatus.QUEUED.name,
            nowMs = nowMs,
            reason = boundReason("interrupted") ?: "interrupted",
        )
    }

    override suspend fun listByOwner(ownerId: SimOwnerId): List<MessageRecord> = storageCall {
        messages.listByOwner(ownerId.wire()).map(MessageEntity::toRecord)
    }

    override suspend fun listConversations(): List<StoredMessageConversation> = storageCall {
        messages.listConversations().map(MessageConversationIdentity::parsed)
    }

    override suspend fun listConversation(identity: StoredMessageConversation): List<MessageRecord> = storageCall {
        messages.listConversation(identity.owner.wire(), identity.key).map(MessageEntity::toRecord)
    }

    override suspend fun lastOwner(): SimOwnerId? = storageCall {
        metadata.get(KEY_LAST_OWNER)?.let(SimOwnerId::parse)
    }

    override suspend fun countUnreadIncoming(): Long = storageCall { messages.countUnreadIncoming() }

    override suspend fun setLastOwner(ownerId: SimOwnerId) = storageCall {
        metadata.upsert(MessageMetadataEntity(KEY_LAST_OWNER, ownerId.wire()))
    }

    override suspend fun readHmacProbe(): ByteArray? = storageCall {
        val stored = metadata.get(KEY_HMAC_PROBE) ?: return@storageCall null
        HmacProbeCodec.decode(stored)
    }

    override suspend fun writeHmacProbe(probe: ByteArray) = storageCall {
        metadata.upsert(MessageMetadataEntity(KEY_HMAC_PROBE, HmacProbeCodec.encode(probe)))
    }

    override fun close() {
        database.close()
    }

    companion object {
        const val FILE_NAME = "softvowifi-messages.db"

        fun open(
            context: Context,
            hmac: AndroidSimOwnerHmac,
            queryContext: CoroutineContext = Dispatchers.IO.limitedParallelism(1),
        ): AndroidMessageRepository {
            val appContext = context.applicationContext
            requireCredentialEncryptedStorage(appContext.isDeviceProtectedStorage)
            val dbFile = appContext.getDatabasePath(FILE_NAME)
            if (dbFile.exists() && !hmac.aliasExists()) {
                throw MessageStorageException()
            }
            if (!hmac.aliasExists()) {
                hmac.ensureKey()
            }
            val database = Room.databaseBuilder(
                appContext,
                SoftVoWiFiMessageDatabase::class.java,
                FILE_NAME,
            )
                .addMigrations(MESSAGE_READ_MIGRATION_1_2)
                .setDriver(AndroidSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(queryContext)
                .build()
            val repository = AndroidMessageRepository(database)
            val current = hmac.probe() ?: run {
                database.close()
                throw MessageStorageException()
            }
            try {
                runBlocking(queryContext) {
                    val stored = repository.readHmacProbe()
                    when {
                        stored == null -> repository.writeHmacProbe(current)
                        !constantTimeEquals(stored, current) -> throw MessageStorageException()
                    }
                }
            } catch (error: Exception) {
                database.close()
                throw if (error is MessageStorageException) error else MessageStorageException()
            }
            return repository
        }
    }
}

private suspend fun <T> storageCall(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: MessageStorageException) {
    throw error
} catch (_: Exception) {
    throw MessageStorageException()
}

private fun boundReason(reason: String): String? = reason.take(32)

private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var acc = 0
    for (index in left.indices) {
        acc = acc or (left[index].toInt() xor right[index].toInt())
    }
    return acc == 0
}

private const val KEY_LAST_OWNER = "last_owner"
private const val KEY_HMAC_PROBE = "hmac_probe"
