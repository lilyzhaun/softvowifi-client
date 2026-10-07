package com.vowifi.sw.manager.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus

@Entity(
    tableName = "messages",
    indices = [
        Index(
            name = "index_messages_owner_direction_engine_sms_id",
            value = ["sim_owner_id", "direction", "engine_sms_id"],
            unique = true,
        ),
        Index(
            name = "index_messages_owner_conversation_sort",
            value = ["sim_owner_id", "conversation_key", "message_at_ms", "message_id"],
        ),
    ],
)
internal data class MessageEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "sim_owner_id") val simOwnerId: String,
    @ColumnInfo(name = "direction") val direction: String,
    @ColumnInfo(name = "peer_address") val peerAddress: String,
    @ColumnInfo(name = "conversation_key") val conversationKey: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "message_at_ms") val messageAtMs: Long,
    @ColumnInfo(name = "engine_sms_id") val engineSmsId: String?,
    @ColumnInfo(name = "outgoing_status") val outgoingStatus: String?,
    @ColumnInfo(name = "engine_status") val engineStatus: String?,
    @ColumnInfo(name = "status_updated_at_ms") val statusUpdatedAtMs: Long,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
    @ColumnInfo(name = "read", defaultValue = "1") val isRead: Boolean = true,
    @ColumnInfo(name = "read_at_ms") val readAtMs: Long? = null,
) {
    override fun toString(): String = "MessageEntity"
}

@Entity(tableName = "message_metadata")
internal data class MessageMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
) {
    override fun toString(): String = "MessageMetadataEntity"
}

@Dao
internal interface MessageDao {
    @Query("SELECT message_id FROM messages WHERE sim_owner_id = :owner AND conversation_key = :key ORDER BY message_at_ms DESC, message_id DESC LIMIT 1")
    suspend fun latestMessageId(owner: String, key: String): String?

    @Query("SELECT COUNT(*) FROM messages WHERE sim_owner_id = :owner AND conversation_key = :key AND direction = 'INCOMING' AND `read` = 0")
    suspend fun conversationUnreadCount(owner: String, key: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putManualUnread(entity: MessageMetadataEntity)

    @Query("DELETE FROM message_metadata WHERE `key` = :key")
    suspend fun removeManualUnread(key: String)

    @Query("DELETE FROM message_metadata WHERE `key` = :key AND value IN (:ids)")
    suspend fun clearObservedManualUnread(key: String, ids: List<String>): Int

    @Query("DELETE FROM messages WHERE sim_owner_id = :owner AND conversation_key = :key")
    suspend fun deleteConversation(owner: String, key: String)

    @Transaction
    suspend fun changeConversation(owner: String, key: String, action: MessageConversationAction): Boolean {
        val anchor = latestMessageId(owner, key) ?: return false
        val marker = "conversation_unread_v1:$owner:$key"
        when (action) {
            MessageConversationAction.MARK_UNREAD -> if (conversationUnreadCount(owner, key) == 0L) {
                putManualUnread(MessageMetadataEntity(marker, anchor))
            }
            MessageConversationAction.DELETE -> {
                removeManualUnread(marker)
                deleteConversation(owner, key)
            }
        }
        return true
    }

    @Query("SELECT * FROM messages WHERE sim_owner_id = :ownerId AND conversation_key = :key AND direction = 'INCOMING' AND `read` = 0 ORDER BY message_at_ms DESC, message_id DESC LIMIT 6")
    suspend fun unreadNotificationPreview(ownerId: String, key: String): List<MessageEntity>

    @Insert
    suspend fun insert(entity: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    suspend fun findById(messageId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE sim_owner_id = :ownerId AND direction = :direction AND engine_sms_id = :engineSmsId LIMIT 1",
    )
    suspend fun findByEngineSmsId(ownerId: String, direction: String, engineSmsId: String): MessageEntity?

    @Query(
        "UPDATE messages SET engine_sms_id = :engineSmsId, outgoing_status = :status, status_updated_at_ms = :nowMs WHERE message_id = :messageId AND sim_owner_id = :ownerId AND direction = :outgoing AND outgoing_status = :queued",
    )
    suspend fun bindAccepted(
        messageId: String,
        ownerId: String,
        outgoing: String,
        engineSmsId: String,
        status: String,
        queued: String,
        nowMs: Long,
    )

    @Query(
        "UPDATE messages SET outgoing_status = :outgoingStatus, engine_status = :engineStatus, status_updated_at_ms = :nowMs, failure_reason = :failureReason WHERE message_id = :messageId AND sim_owner_id = :ownerId AND direction = :outgoing",
    )
    suspend fun updateStatus(
        messageId: String,
        ownerId: String,
        outgoing: String,
        outgoingStatus: String?,
        engineStatus: String?,
        nowMs: Long,
        failureReason: String?,
    )

    @Query(
        "UPDATE messages SET outgoing_status = :outgoingStatus, engine_status = :engineStatus, status_updated_at_ms = :nowMs, failure_reason = :failureReason WHERE message_id = :messageId AND sim_owner_id = :ownerId AND direction = :outgoing AND outgoing_status = :queued",
    )
    suspend fun markQueued(
        messageId: String,
        ownerId: String,
        outgoing: String,
        queued: String,
        outgoingStatus: String,
        engineStatus: String?,
        nowMs: Long,
        failureReason: String?,
    )

    @Query(
        "UPDATE messages SET outgoing_status = :outgoingStatus, status_updated_at_ms = :nowMs, failure_reason = :failureReason WHERE message_id = :messageId AND sim_owner_id = :ownerId AND direction = :outgoing AND outgoing_status = :queued",
    )
    suspend fun markQueuedUnknown(
        messageId: String,
        ownerId: String,
        outgoing: String,
        queued: String,
        outgoingStatus: String,
        nowMs: Long,
        failureReason: String?,
    )

    @Query(
        "UPDATE messages SET outgoing_status = :unknown, status_updated_at_ms = :nowMs, failure_reason = :reason WHERE sim_owner_id = :ownerId AND direction = :outgoing AND outgoing_status = :queued",
    )
    suspend fun interruptQueued(ownerId: String, unknown: String, outgoing: String, queued: String, nowMs: Long, reason: String)

    @Query("SELECT * FROM messages WHERE sim_owner_id = :ownerId ORDER BY message_at_ms ASC, message_id ASC")
    suspend fun listByOwner(ownerId: String): List<MessageEntity>

    @Query("SELECT DISTINCT sim_owner_id AS owner, conversation_key AS conversationKey FROM messages")
    suspend fun listConversations(): List<MessageConversationIdentity>

    @Query("SELECT * FROM messages WHERE sim_owner_id = :owner AND conversation_key = :conversationKey ORDER BY message_at_ms ASC, message_id ASC")
    suspend fun listConversation(owner: String, conversationKey: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE (direction = 'INCOMING' AND `read` = 0) OR message_id IN (SELECT value FROM message_metadata WHERE `key` GLOB 'conversation_unread_v1:*')")
    suspend fun countUnreadIncoming(): Long

    @Query(MessageReadSql.LIST_OBSERVED_UNREAD)
    suspend fun listObservedUnread(
        ownerId: String,
        conversationKey: String,
        observedIds: List<String>,
    ): List<String>

    @Query(MessageReadSql.MARK_OBSERVED_UNREAD)
    suspend fun markObservedUnread(
        ownerId: String,
        conversationKey: String,
        observedIds: List<String>,
        nowMs: Long,
    )

    @Transaction
    suspend fun markIncomingRead(
        ownerId: String,
        conversationKey: String,
        observedIds: List<String>,
        nowMs: Long,
    ): IncomingReadUpdate {
        val updated = markObservedUnreadChunked(
            observedIds = observedIds,
            listChunk = { chunk -> listObservedUnread(ownerId, conversationKey, chunk) },
            markChunk = { chunk -> markObservedUnread(ownerId, conversationKey, chunk, nowMs) },
        )
        var cleared = false
        for (chunk in MessageReadSql.observedIdChunks(observedIds)) {
            if (clearObservedManualUnread("conversation_unread_v1:$ownerId:$conversationKey", chunk) > 0) cleared = true
        }
        return IncomingReadUpdate.of(updated, cleared)
    }

    @Transaction
    suspend fun applyReportedStatus(
        ownerId: String,
        direction: String,
        engineSmsId: String,
        reported: SmsEngineStatus,
        nowMs: Long,
    ) {
        val current = findByEngineSmsId(ownerId, direction, engineSmsId) ?: return
        val currentEngine = current.engineStatus?.let { runCatching { SmsEngineStatus.valueOf(it) }.getOrNull() }
        val next = SmsStatusMachine.reduce(currentEngine, reported)
        val nextUi = next.toOutgoingUiStatus()
        if (next.name == current.engineStatus && nextUi.name == current.outgoingStatus) return
        updateStatus(
            messageId = current.messageId,
            ownerId = ownerId,
            outgoing = direction,
            outgoingStatus = nextUi.name,
            engineStatus = next.name,
            nowMs = nowMs,
            failureReason = current.failureReason,
        )
    }
}

@Dao
internal interface MessageMetadataDao {
    @Query("SELECT COALESCE(MAX(CAST(value AS INTEGER)), 0) FROM message_metadata WHERE `key` GLOB 'owner_ordinal_v1:*'")
    suspend fun maximumOrdinal(): Long

    @Query("DELETE FROM message_metadata WHERE `key` = :key")
    suspend fun delete(key: String)

    @Transaction
    suspend fun ownerLabels(owners: List<String>): Map<String, MessageOwnerLabel> = owners.associateWith { owner ->
        val key = "owner_ordinal_v1:$owner"
        val ordinal = get(key)?.toLong() ?: (maximumOrdinal() + 1).also {
            upsert(MessageMetadataEntity(key, it.toString()))
        }
        MessageOwnerLabel(ordinal, get("owner_remark_v1:$owner").orEmpty())
    }

    @Transaction
    suspend fun saveOwnerRemark(owner: String, value: String): MessageOwnerLabel {
        val key = "owner_remark_v1:$owner"
        if (value.isEmpty()) delete(key) else upsert(MessageMetadataEntity(key, value))
        return ownerLabels(listOf(owner)).getValue(owner)
    }

    @Query("SELECT value FROM message_metadata WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageMetadataEntity)
}

@Database(
    entities = [MessageEntity::class, MessageMetadataEntity::class],
    version = 2,
    exportSchema = true,
)
internal abstract class SoftVoWiFiMessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun metadataDao(): MessageMetadataDao
    abstract fun historyDao(): AndroidMessageHistoryDao
}

internal fun MessageEntity.toRecord(): MessageRecord = if (direction == MESSAGE_DIRECTION_INCOMING) {
    MessageRecord.Incoming(
        id = messageId,
        peerLabel = peerAddress,
        preview = body,
        timestampMs = messageAtMs,
        engineSmsId = engineSmsId,
        isRead = isRead,
        readAtMs = readAtMs,
    )
} else {
    MessageRecord.Outgoing(
        id = messageId,
        peerLabel = peerAddress,
        preview = body,
        timestampMs = messageAtMs,
        status = outgoingStatus?.let { runCatching { OutgoingMessageStatus.valueOf(it) }.getOrNull() }
            ?: OutgoingMessageStatus.QUEUED,
        engineSmsId = engineSmsId,
        engineStatus = engineStatus?.let { runCatching { SmsEngineStatus.valueOf(it) }.getOrNull() },
    )
}

private fun SmsEngineStatus.toOutgoingUiStatus(): OutgoingMessageStatus = when (this) {
    SmsEngineStatus.ACCEPTED,
    SmsEngineStatus.PENDING,
    -> OutgoingMessageStatus.ACCEPTED
    SmsEngineStatus.DELIVERED,
    SmsEngineStatus.FORWARDED,
    SmsEngineStatus.REPLACED,
    -> OutgoingMessageStatus.DELIVERY_REPORTED
    SmsEngineStatus.FAILED -> OutgoingMessageStatus.FAILED
}
