package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ActiveEngineSelector
import com.vowifi.sw.manager.data.STORAGE_NOTICE
import com.vowifi.sw.manager.data.STORAGE_SYNC_NOTICE
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagesScreenTest {
    @Test
    fun recipientContractAllowsOptionalPlusAndOneToTwentyDigits() {
        listOf("1", "+1", "12345678901234567890", "+12345678901234567890").forEach { value ->
            assertTrue(isValidMessageRecipient(value), value)
        }
        listOf("", "+", "123456789012345678901", " 123", "12 34", "12-34", "abc").forEach { value ->
            assertFalse(isValidMessageRecipient(value), value)
        }
    }

    @Test
    fun sendingRequiresEngineReadyValidInputAndIdleSource() {
        val ready = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, composerEnabled = true)

        assertTrue(canSendMessage(ready, "+155501", "hello"))
        assertFalse(canSendMessage(ready.copy(sendBusy = true), "+155501", "hello"))
        assertFalse(canSendMessage(ready.copy(composerEnabled = false), "+155501", "hello"))
        assertFalse(canSendMessage(ready.copy(origin = MessageOrigin.DESKTOP_FIXTURE), "+155501", "hello"))
        assertFalse(canSendMessage(MessageUiState.Loading, "+155501", "hello"))
        assertFalse(canSendMessage(ready, "invalid", "hello"))
        assertFalse(canSendMessage(ready, "+155501", "   "))
    }

    @Test
    fun incomingAloneMeansLocalMtReceipt() {
        val incoming = MessageRecord.Incoming("in", "+155501", "hello", 1L)
        val outgoing = outgoing(SmsEngineStatus.DELIVERED)

        assertEquals("本机已收到 MT 短信", messageDirectionLabel(incoming))
        assertEquals("本机发送的短信", messageDirectionLabel(outgoing))
        assertTrue(incoming.isLocalMtReceipt)
        assertFalse(outgoing.isLocalMtReceipt)
    }

    @Test
    fun deliveredForwardedAndReplacedAreStatusReportsNotLocalMt() {
        val expected = mapOf(
            SmsEngineStatus.DELIVERED to "状态报告：已送达",
            SmsEngineStatus.FORWARDED to "状态报告：已转发",
            SmsEngineStatus.REPLACED to "状态报告：已替换",
        )

        expected.forEach { (status, label) ->
            val record = outgoing(status)
            assertEquals(label, messageRecordStatusLabel(record))
            assertEquals("不代表本机收到 MT 短信", messageStatusNote(record))
            assertFalse(record.isLocalMtReceipt)
        }
        assertNull(messageStatusNote(outgoing(SmsEngineStatus.ACCEPTED)))
        assertEquals("发送结果未知", outgoingStatusLabel(OutgoingMessageStatus.UNKNOWN))
    }

    @Test
    fun statePanelsUseFixedPresentationCopy() {
        val failure = messageStateSpec(MessageUiState.Failure("synthetic raw failure"))
        val permission = messageStateSpec(MessageUiState.PermissionDenied("synthetic raw permission"))
        val unavailable = messageStateSpec(MessageUiState.Unavailable("synthetic raw unavailable"))

        assertEquals("无法读取短信会话，请稍后刷新。", failure?.description)
        assertEquals("请在 KernelSU Manager 授权后刷新。", permission?.description)
        assertEquals("当前没有可用的引擎短信会话。", unavailable?.description)
    }

    @Test
    fun readyNoticesDistinguishReadOnlyAndStorageSend() {
        val readOnly = messageStateSpec(
            MessageUiState.Ready(
                records = emptyList(),
                origin = MessageOrigin.ENGINE,
                composerEnabled = false,
                attachmentNotice = ActiveEngineSelector.NONE_MESSAGE,
            ),
        )
        val storage = messageStateSpec(
            MessageUiState.Ready(
                records = emptyList(),
                origin = MessageOrigin.ENGINE,
                composerEnabled = true,
                attachmentNotice = STORAGE_NOTICE,
            ),
        )
        val sync = messageStateSpec(
            MessageUiState.Ready(
                records = emptyList(),
                origin = MessageOrigin.ENGINE,
                composerEnabled = false,
                attachmentNotice = STORAGE_SYNC_NOTICE,
            ),
        )
        val multiple = messageStateSpec(
            MessageUiState.Ready(
                records = emptyList(),
                origin = MessageOrigin.ENGINE,
                composerEnabled = false,
                attachmentNotice = ActiveEngineSelector.MULTIPLE_MESSAGE,
            ),
        )

        assertEquals("消息只读", readOnly?.title)
        assertEquals(StatePanelTone.NEUTRAL, readOnly?.tone)
        assertEquals(ActiveEngineSelector.NONE_MESSAGE, readOnly?.description)
        assertEquals("短信未发送", storage?.title)
        assertEquals(StatePanelTone.ERROR, storage?.tone)
        assertEquals(STORAGE_NOTICE, storage?.description)
        assertEquals("短信未发送", sync?.title)
        assertEquals(StatePanelTone.ERROR, sync?.tone)
        assertEquals(STORAGE_SYNC_NOTICE, sync?.description)
        assertEquals("消息只读", multiple?.title)
        assertEquals(StatePanelTone.NEUTRAL, multiple?.tone)
        assertEquals(ActiveEngineSelector.MULTIPLE_MESSAGE, multiple?.description)
    }

    @Test
    fun conversationGroupsAreEmptyOutsideReadyState() {
        assertEquals(emptyList(), messageConversationGroups(MessageUiState.Loading))
        assertEquals(emptyList(), messageConversationGroups(MessageUiState.Empty))
        assertEquals(emptyList(), messageConversationGroups(MessageUiState.Failure("synthetic failure")))
    }

    @Test
    fun readyConversationSectionsUseGroupedOrderLatestLabelAndCount() {
        val groups = messageConversationGroups(
            MessageUiState.Ready(
                records = listOf(
                    MessageRecord.Incoming("incoming", "+456", "first", 10L),
                    MessageRecord.Outgoing(
                        id = "outgoing",
                        peerLabel = "456",
                        preview = "second",
                        timestampMs = 20L,
                        status = OutgoingMessageStatus.ACCEPTED,
                    ),
                    MessageRecord.Incoming("leading-zero", "0456", "separate", 30L),
                ),
                origin = MessageOrigin.ENGINE,
                composerEnabled = true,
            ),
        )

        assertEquals(listOf("0456", "456"), groups.map { it.displayLabel })
        assertEquals(listOf(1, 2), groups.map { it.records.size })
        assertEquals(listOf("1 条消息", "2 条消息"), groups.map(::messageConversationSectionSummary))
        assertEquals(listOf("leading-zero"), groups[0].records.map { it.id })
        assertEquals(listOf("incoming", "outgoing"), groups[1].records.map { it.id })
    }

    private fun outgoing(engineStatus: SmsEngineStatus) = MessageRecord.Outgoing(
        id = "out-$engineStatus",
        peerLabel = "+155501",
        preview = "hello",
        timestampMs = 1L,
        status = when (engineStatus) {
            SmsEngineStatus.ACCEPTED,
            SmsEngineStatus.PENDING,
            -> OutgoingMessageStatus.ACCEPTED
            SmsEngineStatus.DELIVERED,
            SmsEngineStatus.FORWARDED,
            SmsEngineStatus.REPLACED,
            -> OutgoingMessageStatus.DELIVERY_REPORTED
            SmsEngineStatus.FAILED -> OutgoingMessageStatus.FAILED
        },
        engineStatus = engineStatus,
    )
}
