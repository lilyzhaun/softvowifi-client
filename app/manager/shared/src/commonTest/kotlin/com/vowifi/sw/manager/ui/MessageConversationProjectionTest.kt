package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.STORAGE_NOTICE
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageConversationProjectionTest {
    @Test
    fun alphanumericSenderSurvivesListAndThreadWithoutBecomingReplyRecipient() {
        val state = ready(incoming("sender-name", "ExampleVerify", "synthetic message", 100L))
        val row = projectMessageConversations(owner(), state).rows.single()
        assertEquals("ExampleVerify", row.peerDisplay)
        assertEquals("E", row.avatarText)
        val thread = checkNotNull(projectMessageThread(row.handle, owner(), state) {
            MessageDateSpec("today", "今天")
        })
        assertEquals("ExampleVerify", thread.peerDisplay)
        assertNull(thread.recipient)
        assertEquals("未知号码", privateUiPhoneDisplay("ExampleVerify"))
    }

    @Test
    fun conversationRowsExposeGoogleMessagesFactsWithFullNumberAndAvatarFallback() {
        val state = ready(
            incoming("old", "+15550123", "older", 100L),
            outgoing("latest", "15550123", "latest preview", 300L, OutgoingMessageStatus.UNKNOWN),
            incoming("other", "0456", "other preview", 200L),
        )

        val page = projectMessageConversations(owner(), state)

        assertEquals(listOf("15550123", "0456"), page.rows.map { it.peerDisplay })
        assertEquals(listOf("1", "0"), page.rows.map { it.avatarText })
        assertEquals(listOf("latest preview", "other preview"), page.rows.map { it.latestPreview })
        assertEquals(listOf(2, 1), page.rows.map { it.recordCount })
        assertEquals(listOf(1, 1), page.rows.map { it.unreadCount })
        assertEquals(listOf("发送结果未知", null), page.rows.map { it.outgoingStatus })
        assertEquals(listOf(300L, 200L), page.rows.map { it.timestamp.epochMs })
        assertNotEquals(page.rows[0].handle, page.rows[1].handle)
        assertEquals("ConversationHandle", page.rows[0].handle.toString())
        assertEquals("MessageConversationRow(recordCount=2, unreadCount=1)", page.rows[0].toString())
        assertFalse(page.rows[0].toString().contains("15550123"))
    }

    @Test
    fun readIncomingAndOutgoingRowsDoNotCountAsUnread() {
        val page = projectMessageConversations(
            owner(),
            ready(
                incoming("read-in", "+15550999", "already seen", 50L, isRead = true),
                outgoing("only-out", "+15550888", "sent", 40L, OutgoingMessageStatus.ACCEPTED),
            ),
        )
        assertEquals(listOf(0, 0), page.rows.map { it.unreadCount })
    }

    @Test
    fun threadIsChronologicalWithDateSeparatorsAndTypedBubbles() {
        val state = ready(
            outgoing("delivery", "+155501", "delivered", 300L, OutgoingMessageStatus.DELIVERY_REPORTED),
            incoming("incoming", "155501", "received", 100L),
            outgoing("accepted", "155501", "accepted", 200L, OutgoingMessageStatus.ACCEPTED),
        )
        val page = projectMessageConversations(owner(), state)

        val thread = checkNotNull(
            projectMessageThread(page.rows.single().handle, owner(), state) { timestamp ->
                if (timestamp < 300L) MessageDateSpec("day-1", "第一天") else MessageDateSpec("day-2", "第二天")
            },
        )

        assertEquals(
            listOf("date:day-1", "incoming:incoming", "outgoing:accepted", "date:day-2", "outgoing:delivery"),
            thread.items.map { item ->
                when (item) {
                    is MessageThreadItem.DateSeparator -> "date:${item.date.key}"
                    is MessageThreadItem.IncomingBubble -> "incoming:${item.recordId}"
                    is MessageThreadItem.OutgoingBubble -> "outgoing:${item.recordId}"
                }
            },
        )
        val delivery = assertIs<MessageThreadItem.OutgoingBubble>(thread.items.last())
        assertEquals("+155501", thread.peerDisplay)
        assertFalse(thread.toString().contains("155501"))
        assertEquals("状态报告：已送达", delivery.status)
        assertEquals("不代表本机收到 MT 短信", delivery.statusNote)
        assertFalse(delivery.isIncoming)
        assertTrue(assertIs<MessageThreadItem.IncomingBubble>(thread.items[1]).isIncoming)
    }

    @Test
    fun unsafePeerAndContentNeverReachPresentationOrHandleStrings() {
        val unsafe = "sip:user@example.invalid\u0000 raw: owner=synthetic"
        val state = ready(incoming("unsafe", unsafe, unsafe.repeat(20), 100L))

        val page = projectMessageConversations(owner(), state)
        val row = page.rows.single()
        val thread = projectMessageThread(row.handle, owner(), state) { MessageDateSpec("day", "某日") }

        assertEquals("未知号码", row.peerDisplay)
        assertEquals("?", row.avatarText)
        assertEquals("内容不可显示", row.latestPreview)
        assertEquals("内容不可显示", assertIs<MessageThreadItem.IncomingBubble>(thread?.items?.last()).body)
        assertFalse(row.handle.value.contains("sip", ignoreCase = true))
        assertFalse(row.toString().contains("owner", ignoreCase = true))
    }

    @Test
    fun composePolicyPreservesEngineOnlyReadOnlyStorageAndBusyTruth() {
        val writable = projectMessageConversations(owner(), ready())
        val busy = projectMessageConversations(owner(), ready(sendBusy = true))
        val readOnly = projectMessageConversations(owner(), ready(composerEnabled = false))
        val storage = projectMessageConversations(owner(), ready(attachmentNotice = STORAGE_NOTICE))
        val fixture = projectMessageConversations(owner(), ready(origin = MessageOrigin.DESKTOP_FIXTURE))
        val notReady = projectMessageConversations(owner(), MessageUiState.Loading)

        assertEquals(MessageComposeMode.ENABLED, writable.compose.mode)
        assertEquals(MessageComposeMode.BUSY, busy.compose.mode)
        assertEquals(MessageComposeMode.READ_ONLY, readOnly.compose.mode)
        assertEquals(MessageComposeMode.STORAGE_BLOCKED, storage.compose.mode)
        assertEquals(MessageComposeMode.FIXTURE_READ_ONLY, fixture.compose.mode)
        assertEquals(MessageComposeMode.NOT_READY, notReady.compose.mode)
        assertEquals(true, writable.compose.editingEnabled)
        assertEquals(true, writable.compose.sendingEnabled)
        assertEquals(false, busy.compose.editingEnabled)
        assertEquals(false, busy.compose.sendingEnabled)
        assertEquals(true, readOnly.compose.editingEnabled)
        assertEquals(false, readOnly.compose.sendingEnabled)
        assertEquals(false, storage.compose.editingEnabled)
        assertEquals(false, storage.compose.sendingEnabled)
        assertEquals(true, fixture.compose.editingEnabled)
        assertEquals(false, fixture.compose.sendingEnabled)
        assertEquals(false, notReady.compose.editingEnabled)
        assertEquals(false, notReady.compose.sendingEnabled)
    }

    @Test
    fun messageRouteTitlesKeepRootCleanAndThreadNumberComplete() {
        val state = ready(incoming("one", "+15550123", "body", 100L))
        val row = projectMessageConversations(messageProjectionOwner(state), state).rows.single()

        assertEquals("短信", messageRouteTopBarTitle(AppRoute.Messages, state))
        assertEquals("+15550123", messageRouteTopBarTitle(AppRoute.MessageThread(row.handle), state))
        assertEquals("新建短信", messageRouteTopBarTitle(AppRoute.NewMessage, state))
    }

    @Test
    fun malformedOrStaleConversationHandlesAreRejectedWithoutFallbackSelection() {
        assertFailsWith<IllegalArgumentException> { ConversationHandle.fromOpaque("+155501") }
        assertFailsWith<IllegalArgumentException> { ConversationHandle.fromOpaque("conversation-sip:user") }

        val state = ready(incoming("one", "+155501", "body", 100L))
        val stale = ConversationHandle.fromOpaque("conversation-stale")

        assertNull(projectMessageThread(stale, owner(), state) { MessageDateSpec("day", "某日") })
    }

    @Test
    fun handleIsStableAcrossInputOrderAndStorageWarningKeepsThreadReadable() {
        val first = incoming("first", "+155501", "first", 100L)
        val second = outgoing("second", "155501", "second", 200L, OutgoingMessageStatus.ACCEPTED)
        val owner = owner()
        val normal = ready(first, second)
        val staleOrder = ready(second, first, attachmentNotice = STORAGE_NOTICE)

        val normalHandle = projectMessageConversations(owner, normal).rows.single().handle
        val warnedPage = projectMessageConversations(owner, staleOrder)
        val warnedThread = projectMessageThread(warnedPage.rows.single().handle, owner, staleOrder) {
            MessageDateSpec("day", "某日")
        }

        assertEquals(normalHandle, warnedPage.rows.single().handle)
        assertEquals(MessageComposeMode.STORAGE_BLOCKED, warnedThread?.compose?.mode)
        assertEquals(3, warnedThread?.items?.size)
    }

    private fun owner(): SimOwnerId = checkNotNull(SimOwnerId.fromHmacSha256(ByteArray(32) { 7 }))

    private fun ready(
        vararg records: MessageRecord,
        origin: MessageOrigin = MessageOrigin.ENGINE,
        sendBusy: Boolean = false,
        composerEnabled: Boolean = true,
        attachmentNotice: String? = null,
    ) = MessageUiState.Ready(records.toList(), origin, sendBusy, composerEnabled, attachmentNotice, owner())

    private fun incoming(
        id: String,
        peer: String,
        body: String,
        timestamp: Long,
        isRead: Boolean = false,
    ) = MessageRecord.Incoming(id, peer, body, timestamp, isRead = isRead)

    private fun outgoing(
        id: String,
        peer: String,
        body: String,
        timestamp: Long,
        status: OutgoingMessageStatus,
    ) = MessageRecord.Outgoing(
        id = id,
        peerLabel = peer,
        preview = body,
        timestampMs = timestamp,
        status = status,
        engineStatus = if (status == OutgoingMessageStatus.DELIVERY_REPORTED) SmsEngineStatus.DELIVERED else null,
    )
}
