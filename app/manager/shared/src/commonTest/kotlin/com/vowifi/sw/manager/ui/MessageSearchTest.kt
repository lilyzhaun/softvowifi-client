package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageSearchTest {
    @Test
    fun emptyOrBlankQueryReturnsRowsUnchangedAndInOrder() {
        val row1 = row(handle = "conversation-1-1", peer = "+15550100", preview = "First message")
        val row2 = row(handle = "conversation-1-2", peer = "+15550200", preview = "Second message")
        val rows = listOf(row1, row2)

        val emptyResult = filterMessageConversationRows(rows, "")
        val blankResult = filterMessageConversationRows(rows, "   ")

        assertEquals(rows, emptyResult)
        assertEquals(rows, blankResult)
    }

    @Test
    fun peerDisplayMatchingIsCaseInsensitiveAndPreservesOrder() {
        val row1 = row(handle = "conversation-1-1", peer = "Alice", preview = "Hello")
        val row2 = row(handle = "conversation-1-2", peer = "BOB", preview = "Hi")
        val row3 = row(handle = "conversation-1-3", peer = "alice in wonderland", preview = "Hey")
        val rows = listOf(row1, row2, row3)

        val result = filterMessageConversationRows(rows, "alice")

        assertEquals(listOf(row1, row3), result)
    }

    @Test
    fun latestPreviewMatchingWorksWhileOutgoingStatusAndOpaqueHandleNeverParticipate() {
        val row1 = row(handle = "conversation-match-handle", peer = "123", preview = "Keyword target", status = "排队中")
        val row2 = row(handle = "conversation-keyword-handle", peer = "456", preview = "Other text", status = "KeywordStatus")
        val rows = listOf(row1, row2)

        val targetResult = filterMessageConversationRows(rows, "TARGET")
        val statusOrHandleResult = filterMessageConversationRows(rows, "KeywordStatus")
        val handleOnlyResult = filterMessageConversationRows(rows, "conversation-keyword")

        assertEquals(listOf(row1), targetResult)
        assertEquals(emptyList(), statusOrHandleResult)
        assertEquals(emptyList(), handleOnlyResult)
    }

    @Test
    fun boundedMessageSearchQueryStripsIsoControlsAndCapsAt80Characters() {
        val withControls = "hello\u0000\nworld\r\t!"
        val longQuery = "a".repeat(100)

        val cleanResult = boundedMessageSearchQuery(withControls)
        val cappedResult = boundedMessageSearchQuery(longQuery)

        assertEquals("helloworld!", cleanResult)
        assertEquals(80, cappedResult.length)
        assertEquals("a".repeat(80), cappedResult)
    }

    @Test
    fun messageListChromeContractSpecifiesExactRootLayoutAndChromeOwnership() {
        val contract = messageListChromeContract()

        assertTrue(contract.searchCapsule)
        assertTrue(contract.insideSingleLazyColumn)
        assertFalse(contract.expandedResultsSurface)
        assertTrue(contract.tabularTime)
        assertFalse(contract.perRowCard)
    }

    @Test
    fun dedicatedNoMatchStateIsDistinctFromExistingEmptyStateAndNeverEchoesQuery() {
        val searchEmpty = messageSearchEmptySpec()

        assertEquals("未找到匹配会话", searchEmpty.title)
        assertEquals("尝试使用不同的号码或短信内容关键词搜索。", searchEmpty.description)
        assertFalse(searchEmpty.title.contains("暂无会话"))
    }

    private fun row(
        handle: String,
        peer: String,
        preview: String,
        status: String? = null,
    ) = MessageConversationRow(
        handle = ConversationHandle.fromOpaque(handle),
        peerDisplay = peer,
        avatarText = peer.take(1),
        latestPreview = preview,
        timestamp = MessageTimestampSpec(1000L),
        recordCount = 1,
        unreadCount = 0,
        outgoingStatus = status,
    )
}
