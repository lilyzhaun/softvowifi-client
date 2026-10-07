package com.vowifi.sw.manager.data

data class IncomingInsertResult(
    val inserted: Boolean,
    val messageId: String,
) {
    override fun toString(): String = "IncomingInsertResult(inserted=$inserted)"
}

class IncomingReadUpdate private constructor(ids: List<String>, val manualUnreadCleared: Boolean) {
    private val ids: List<String> = ids.toList()
    val updatedIds: List<String> get() = ids.toList()
    val updatedCount: Int get() = ids.size

    override fun toString(): String = "IncomingReadUpdate(updatedCount=$updatedCount)"

    companion object {
        fun of(updatedIds: Collection<String>, manualUnreadCleared: Boolean = false): IncomingReadUpdate =
            IncomingReadUpdate(updatedIds.toList(), manualUnreadCleared)
    }
}

class ObservedIncomingReadRequest private constructor(
    val ownerId: SimOwnerId,
    val conversationKey: String,
    ids: List<String>,
) {
    private val ids: List<String> = ids.toList()
    val observedMessageIds: List<String> get() = ids.toList()

    override fun toString(): String =
        "ObservedIncomingReadRequest(observedCount=${ids.size})"

    companion object {
        fun create(
            ownerId: SimOwnerId,
            conversationKey: String,
            observedMessageIds: Collection<String>,
        ): ObservedIncomingReadRequest? {
            if (conversationKey.isEmpty() || conversationKey.any(Char::isISOControl)) return null
            val ids = observedMessageIds.filter { id ->
                id.isNotEmpty() && id.none(Char::isISOControl)
            }.distinct()
            return ObservedIncomingReadRequest(ownerId, conversationKey, ids)
        }
    }
}

internal const val MESSAGE_DIRECTION_INCOMING = "INCOMING"
internal const val MESSAGE_DIRECTION_OUTGOING = "OUTGOING"

internal data class BoundObservedSql(val sql: String, val args: List<String>) {
    override fun toString(): String = "BoundObservedSql(placeholders=${args.size})"
}

internal object MessageReadSql {
    const val OBSERVED_ID_BIND_LIMIT = 996
    const val ADD_READ_COLUMN =
        "ALTER TABLE messages ADD COLUMN `read` INTEGER NOT NULL DEFAULT 1"
    const val ADD_READ_AT_COLUMN =
        "ALTER TABLE messages ADD COLUMN `read_at_ms` INTEGER"
    const val STAMP_EXISTING_INCOMING_READ_AT =
        "UPDATE messages SET `read_at_ms` = status_updated_at_ms WHERE direction = 'INCOMING'"
    const val LIST_OBSERVED_UNREAD =
        "SELECT message_id FROM messages WHERE sim_owner_id = :ownerId AND conversation_key = :conversationKey AND direction = 'INCOMING' AND `read` = 0 AND message_id IN (:observedIds)"
    const val MARK_OBSERVED_UNREAD =
        "UPDATE messages SET `read` = 1, read_at_ms = :nowMs WHERE sim_owner_id = :ownerId AND conversation_key = :conversationKey AND direction = 'INCOMING' AND `read` = 0 AND message_id IN (:observedIds)"

    val migrate1to2Statements: List<String> = listOf(
        ADD_READ_COLUMN,
        ADD_READ_AT_COLUMN,
        STAMP_EXISTING_INCOMING_READ_AT,
    )

    fun observedIdChunks(ids: List<String>): List<List<String>> =
        if (ids.isEmpty()) emptyList() else ids.chunked(OBSERVED_ID_BIND_LIMIT)

    fun bindListObservedUnread(
        ownerId: String,
        conversationKey: String,
        observedIds: List<String>,
    ): BoundObservedSql = bindNamed(
        LIST_OBSERVED_UNREAD,
        mapOf("ownerId" to ownerId, "conversationKey" to conversationKey),
        observedIds,
    )

    fun bindMarkObservedUnread(
        ownerId: String,
        conversationKey: String,
        observedIds: List<String>,
        nowMs: Long,
    ): BoundObservedSql = bindNamed(
        MARK_OBSERVED_UNREAD,
        mapOf(
            "nowMs" to nowMs.toString(),
            "ownerId" to ownerId,
            "conversationKey" to conversationKey,
        ),
        observedIds,
    )
}

internal suspend fun markObservedUnreadChunked(
    observedIds: List<String>,
    listChunk: suspend (List<String>) -> List<String>,
    markChunk: suspend (List<String>) -> Unit,
): List<String> {
    val updated = ArrayList<String>()
    for (chunk in MessageReadSql.observedIdChunks(observedIds)) {
        val ids = listChunk(chunk)
        if (ids.isEmpty()) continue
        markChunk(ids)
        updated += ids
    }
    return updated
}

internal fun parseObservedIncomingRead(
    ownerWire: String,
    conversationKey: String,
    observedMessageIds: Collection<String>,
): ObservedIncomingReadRequest? {
    val owner = SimOwnerId.parse(ownerWire) ?: return null
    return ObservedIncomingReadRequest.create(owner, conversationKey, observedMessageIds)
}

private fun bindNamed(
    template: String,
    scalars: Map<String, String>,
    observedIds: List<String>,
): BoundObservedSql {
    check(observedIds.isNotEmpty())
    val args = ArrayList<String>(scalars.size + observedIds.size)
    val sql = StringBuilder(template.length + observedIds.size * 2)
    var index = 0
    while (index < template.length) {
        val char = template[index]
        if (char == ':' && index + 1 < template.length && template[index + 1].isLetter()) {
            val start = index + 1
            var end = start
            while (end < template.length && template[end].isLetterOrDigit()) end++
            val name = template.substring(start, end)
            if (name == "observedIds") {
                observedIds.forEachIndexed { offset, id ->
                    if (offset > 0) sql.append(',')
                    sql.append('?')
                    args += id
                }
            } else {
                sql.append('?')
                args += scalars.getValue(name)
            }
            index = end
        } else {
            sql.append(char)
            index += 1
        }
    }
    return BoundObservedSql(sql.toString(), args)
}
