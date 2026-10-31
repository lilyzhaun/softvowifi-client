package com.vowifi.sw.manager.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

internal data class MessageOwnerAggregate(
    val owner: String,
    val conversationCount: Long,
    val messageCount: Long,
    val unreadCount: Long,
) {
    override fun toString(): String = "MessageOwnerAggregate"
}

internal data class MessageSummaryEntity(
    val conversationKey: String,
    val peerAddress: String,
    val body: String,
    val messageId: String,
    val messageAtMs: Long,
    val direction: String,
    val outgoingStatus: String?,
    val engineStatus: String?,
    val messageCount: Long,
    val unreadCount: Long,
) {
    override fun toString(): String = "MessageSummaryEntity"
}

@Dao
internal interface AndroidMessageHistoryDao {
    @Query("""
        SELECT sim_owner_id AS owner, COUNT(DISTINCT conversation_key) AS conversationCount,
            COUNT(*) AS messageCount,
            SUM(CASE WHEN (direction = 'INCOMING' AND `read` = 0) OR message_id IN
                (SELECT value FROM message_metadata WHERE `key` GLOB 'conversation_unread_v1:*') THEN 1 ELSE 0 END) AS unreadCount
        FROM messages WHERE (:after IS NULL OR sim_owner_id > :after)
        GROUP BY sim_owner_id ORDER BY sim_owner_id ASC LIMIT 51
    """)
    suspend fun owners(after: String?): List<MessageOwnerAggregate>

    @Query("""
        SELECT latest.conversation_key AS conversationKey, latest.peer_address AS peerAddress,
            latest.body AS body, latest.message_id AS messageId, latest.message_at_ms AS messageAtMs,
            latest.direction AS direction, latest.outgoing_status AS outgoingStatus, latest.engine_status AS engineStatus,
            totals.messageCount AS messageCount, totals.unreadCount AS unreadCount
        FROM (
            SELECT conversation_key, COUNT(*) AS messageCount,
                SUM(CASE WHEN (direction = 'INCOMING' AND `read` = 0) OR message_id IN
                    (SELECT value FROM message_metadata WHERE `key` GLOB 'conversation_unread_v1:*') THEN 1 ELSE 0 END) AS unreadCount
            FROM messages WHERE sim_owner_id = :owner GROUP BY conversation_key
        ) AS totals
        JOIN messages AS latest ON latest.message_id = (
            SELECT message_id FROM messages
            WHERE sim_owner_id = :owner AND conversation_key = totals.conversation_key
            ORDER BY message_at_ms DESC, message_id DESC LIMIT 1
        )
        WHERE (:beforeTime IS NULL OR latest.message_at_ms < :beforeTime
            OR (latest.message_at_ms = :beforeTime AND latest.message_id < :beforeId)
            OR (latest.message_at_ms = :beforeTime AND latest.message_id = :beforeId AND latest.conversation_key < :beforeKey))
        ORDER BY latest.message_at_ms DESC, latest.message_id DESC, latest.conversation_key DESC LIMIT 100
    """)
    suspend fun summaryBatch(owner: String, beforeTime: Long?, beforeId: String?, beforeKey: String?): List<MessageSummaryEntity>

    @Transaction
    suspend fun summaryPage(request: MessageSummaryRequest): MessageSummaryPage = collectMessageSummaryPage(request) { cursor ->
        summaryBatch(request.owner.wire(), cursor?.timestampMs, cursor?.messageId, cursor?.conversationKey)
    }

    @Query("""
        SELECT * FROM messages WHERE sim_owner_id = :owner AND conversation_key = :key
            AND (:beforeTime IS NULL OR message_at_ms < :beforeTime
                OR (message_at_ms = :beforeTime AND message_id < :beforeId))
        ORDER BY message_at_ms DESC, message_id DESC LIMIT 51
    """)
    suspend fun thread(owner: String, key: String, beforeTime: Long?, beforeId: String?): List<MessageEntity>

    @Query("SELECT message_id FROM messages WHERE sim_owner_id = :owner AND conversation_key = :key LIMIT 1")
    suspend fun conversationMessageId(owner: String, key: String): String?

    @Query("""
        SELECT message_id FROM messages WHERE sim_owner_id = :owner AND conversation_key = :key
            AND direction = 'INCOMING' AND `read` = 0 ORDER BY message_at_ms ASC, message_id ASC
    """)
    suspend fun unreadIds(owner: String, key: String): List<String>
}
