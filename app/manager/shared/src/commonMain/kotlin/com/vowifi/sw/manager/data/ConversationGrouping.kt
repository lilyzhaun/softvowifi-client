package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord

data class ConversationGroup(
    val conversationKey: String,
    val displayLabel: String,
    val records: List<MessageRecord>,
) {
    override fun toString(): String = "ConversationGroup(recordCount=${records.size})"
}

object ConversationGrouping {
    fun group(records: List<MessageRecord>): List<ConversationGroup> = records
        .groupBy { PeerNumberNormalizer.normalize(it.peerLabel) }
        .map { (conversationKey, groupedRecords) ->
            val sortedRecords = groupedRecords.sortedWith(recordComparator)
            ConversationGroup(
                conversationKey = conversationKey,
                displayLabel = sortedRecords.last().peerLabel,
                records = sortedRecords,
            )
        }
        .sortedWith(groupComparator)
}

private val recordComparator = compareBy<MessageRecord>(MessageRecord::timestampMs)
    .thenBy(MessageRecord::id)

private val groupComparator = compareByDescending<ConversationGroup> { it.records.last().timestampMs }
    .thenByDescending { it.records.last().id }
    .thenBy(ConversationGroup::conversationKey)
