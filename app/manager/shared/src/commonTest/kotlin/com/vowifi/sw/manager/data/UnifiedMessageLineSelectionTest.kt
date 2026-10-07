@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.selectableLines
import com.vowifi.sw.manager.ui.pickerTitle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UnifiedMessageLineSelectionTest {
    @Test fun disconnectedRunningEngineRemainsSelectableAndStoppedHistoryIsNot() = runTest {
        val a = requireNotNull(ownerFor(EngineMessagePersistenceFixtures.ICCID_A))
        val b = requireNotNull(ownerFor(EngineMessagePersistenceFixtures.ICCID_B))
        val repository = FakeMessageRepository()
        repository.insertIncomingIfAbsent(b, "old", "old", "155501", "synthetic", 1, 1)
        val selection = MessageHistorySelection(repository, backgroundScope, MessagePersistenceObserver { _, _, _ -> })
        try {
            selection.refreshInbox(); runCurrent()
            val disconnected = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = a, composerEnabled = false, sessionConnected = false)
            selection.publishLive(disconnected, mapOf(a to disconnected), setOf(a, b), null, true,
                runningLineOwners = setOf(a)); runCurrent()
            val choices = selection.historyChoices.value
            assertEquals(listOf(a), choices.selectableLines().map { it.owner })
            assertEquals("选择短信线路", choices.pickerTitle())
            assertEquals(a, assertIs<MessageUiState.Inbox>(selection.state.value).ownerId)
            assertTrue(assertIs<MessageUiState.Inbox>(selection.state.value).page.items.isEmpty())
            assertFalse(assertIs<MessageUiState.Inbox>(selection.state.value).composerEnabled)
            selection.publishLive(MessageUiState.Unavailable("offline"), runningLineOwners = emptySet()); runCurrent()
            assertEquals("选择短信记录线路", selection.historyChoices.value.pickerTitle())
            selection.selectHistoryOwner(b); runCurrent()
            assertEquals(b, assertIs<MessageUiState.Inbox>(selection.state.value).ownerId)
            assertEquals("old", assertIs<MessageUiState.Inbox>(selection.state.value).page.items.single().latestMessageId)
        } finally { selection.close() }
    }
}
