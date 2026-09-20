package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.MessageOwnerLabel
import com.vowifi.sw.manager.data.MessageRemarkSaveResult
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageHistoryLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageLinePresentationTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))

    @Test fun duplicateRemarksRemainSearchableByStableLocalNumber() {
        val lines = (1..20).map { MessageHistoryLine(owner, it, false, MessageOwnerLabel(it.toLong(), "同名")) }
        assertEquals(20, filterMessageLines(lines, "同名").size)
        assertEquals(listOf(lines.last()), filterMessageLines(lines, " 20 "))
        assertEquals(emptyList(), filterMessageLines(lines, "不存在"))
        assertEquals(lines, filterMessageLines(lines, ""))
    }

    @Test fun actualPendingCallbackBlocksDuplicateSaveAndPreservesDraftOnFailure() = runTest {
        val editor = MessageRemarkEditor(owner, "草稿")
        val response = CompletableDeferred<MessageRemarkSaveResult>()
        var calls = 0
        val submit: suspend (SimOwnerId, String) -> MessageRemarkSaveResult = { target, input ->
            assertEquals(owner, target)
            assertEquals("草稿", input)
            calls++
            response.await()
        }
        val saved = async { editor.save(submit) }
        runCurrent()
        assertTrue(editor.busy)
        assertFalse(editor.save(submit))
        assertEquals(1, calls)
        response.complete(MessageRemarkSaveResult.Failed)
        assertFalse(saved.await())
        assertFalse(editor.busy)
        assertEquals("草稿", editor.input)
        assertEquals(owner, editor.owner)
    }
}
