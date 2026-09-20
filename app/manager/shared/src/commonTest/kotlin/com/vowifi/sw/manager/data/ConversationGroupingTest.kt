package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ConversationGroupingTest {
    @Test
    fun plusAndPlainPeersMergeWhileLeadingZeroPeerStaysSeparate() {
        val groups = ConversationGrouping.group(
            listOf(
                incoming(id = "incoming", peer = "+456", timestampMs = 10),
                outgoing(id = "outgoing", peer = "456", timestampMs = 20),
                incoming(id = "leading-zero", peer = "0456", timestampMs = 30),
            ),
        )

        assertEquals(listOf("0456", "456"), groups.map { it.conversationKey })
        assertEquals(listOf("incoming", "outgoing"), groups[1].records.map { it.id })
        assertIs<MessageRecord.Incoming>(groups[1].records[0])
        assertIs<MessageRecord.Outgoing>(groups[1].records[1])
    }

    @Test
    fun invalidPeersAreIsolatedByTheirExactValues() {
        val groups = ConversationGrouping.group(
            listOf(
                incoming(id = "space-prefix", peer = " 456", timestampMs = 10),
                incoming(id = "space-suffix", peer = "456 ", timestampMs = 20),
                incoming(id = "letters", peer = "peer456", timestampMs = 30),
                incoming(id = "multiple-plus", peer = "++456", timestampMs = 40),
                outgoing(id = "same-multiple-plus", peer = "++456", timestampMs = 50),
            ),
        )

        assertEquals(4, groups.size)
        assertEquals(
            setOf("raw: 456", "raw:456 ", "raw:peer456", "raw:++456"),
            groups.map { it.conversationKey }.toSet(),
        )
        assertEquals(
            listOf("multiple-plus", "same-multiple-plus"),
            groups.first { it.conversationKey == "raw:++456" }.records.map { it.id },
        )
    }

    @Test
    fun recordsSortByTimestampThenIdAndLatestRecordSuppliesDisplayLabel() {
        val group = ConversationGrouping.group(
            listOf(
                incoming(id = "id-b", peer = "+456", timestampMs = 20),
                outgoing(id = "id-c", peer = "456", timestampMs = 20),
                incoming(id = "id-a", peer = "+456", timestampMs = 10),
            ),
        ).single()

        assertEquals(listOf("id-a", "id-b", "id-c"), group.records.map { it.id })
        assertEquals("456", group.displayLabel)
    }

    @Test
    fun groupsSortByLatestTimestampThenLatestIdThenKey() {
        val groups = ConversationGrouping.group(
            listOf(
                incoming(id = "id-a", peer = "300", timestampMs = 30),
                incoming(id = "id-c", peer = "200", timestampMs = 30),
                incoming(id = "id-c", peer = "100", timestampMs = 30),
                incoming(id = "id-z", peer = "400", timestampMs = 20),
            ),
        )

        assertEquals(listOf("100", "200", "300", "400"), groups.map { it.conversationKey })
    }

    @Test
    fun groupingResultStringDoesNotExposeMessageContent() {
        val groups = ConversationGrouping.group(
            listOf(incoming(id = "message-id", peer = "456", timestampMs = 10, body = SYNTH_BODY)),
        )

        assertFalse(groups.toString().contains(SYNTH_BODY))
        assertEquals(
            "[ConversationGroup(recordCount=1)]",
            groups.toString(),
        )
    }
}

private const val SYNTH_BODY = "synthetic-message-body-never-log"

private fun incoming(
    id: String,
    peer: String,
    timestampMs: Long,
    body: String = "synthetic-incoming-body",
): MessageRecord = MessageRecord.Incoming(
    id = id,
    peerLabel = peer,
    preview = body,
    timestampMs = timestampMs,
)

private fun outgoing(
    id: String,
    peer: String,
    timestampMs: Long,
    body: String = "synthetic-outgoing-body",
): MessageRecord = MessageRecord.Outgoing(
    id = id,
    peerLabel = peer,
    preview = body,
    timestampMs = timestampMs,
    status = OutgoingMessageStatus.ACCEPTED,
)
