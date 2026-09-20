package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.MessageRemarkEditor
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageOwnerLabelsSourceTest {
    private val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))

    @Test fun failedLabelReadCanRetryAndMismatchedReadbackCannotChangeDisplay() = runTest {
        val store = FakeMessageRepository()
        var failRead = true
        val repository = object : MessageRepository by store {
            override suspend fun ownerLabels(owners: List<SimOwnerId>): Map<SimOwnerId, MessageOwnerLabel> {
                if (failRead) throw MessageStorageException()
                return store.ownerLabels(owners)
            }
            override suspend fun saveOwnerRemark(owner: SimOwnerId, remark: MessageOwnerRemark) = MessageOwnerLabel(1, "")
        }
        val live = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = ownerA, composerEnabled = true)
        lateinit var inbox: MessageInboxSelection
        inbox = MessageInboxSelection(repository, this) { inbox.render(live) }
        inbox.refresh()
        runCurrent()
        assertTrue(inbox.choices.value.labelsFailed)
        failRead = false
        inbox.refresh()
        runCurrent()
        assertFalse(inbox.choices.value.labelsFailed)
        assertEquals(1L, inbox.choices.value.lines.single().label?.ordinal)
        assertEquals(MessageRemarkSaveResult.Failed, inbox.saveRemark(ownerA, "新备注"))
        assertEquals("", inbox.choices.value.lines.single().label?.remark)
        assertEquals(ownerA, (inbox.render(live) as MessageUiState.Inbox).ownerId)
        assertTrue((inbox.render(live) as MessageUiState.Inbox).composerEnabled)
        inbox.close()
    }

    @Test fun editingOtherLineDoesNotSelectOrMutateSendOwnerAndFailedSaveRetainsInput() = runTest {
        val store = FakeMessageRepository()
        store.setLastOwner(ownerA)
        store.insertIncomingIfAbsent(ownerB, "b", "b", "155501", "synthetic", 1, 1)
        val live = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = ownerA, composerEnabled = true)
        lateinit var inbox: MessageInboxSelection
        inbox = MessageInboxSelection(store, this) { inbox.render(live) }
        inbox.refresh()
        runCurrent()
        val editor = MessageRemarkEditor(ownerB, "")
        editor.input = "旅行备用线路"
        store.failRemark = true
        assertFalse(editor.save(inbox::saveRemark))
        assertEquals("旅行备用线路", editor.input)
        assertEquals(ownerB, editor.owner)
        assertTrue(editor.error != null)
        assertEquals("", inbox.choices.value.lines.single { it.owner == ownerB }.label?.remark)
        store.failRemark = false
        assertTrue(editor.save(inbox::saveRemark))
        assertEquals("旅行备用线路", inbox.choices.value.lines.single { it.owner == ownerB }.label?.remark)
        assertEquals(ownerA, inbox.choices.value.selectedOwner)
        assertEquals(ownerA, (inbox.render(live) as MessageUiState.Inbox).ownerId)
        assertTrue((inbox.render(live) as MessageUiState.Inbox).composerEnabled)
        assertEquals(ownerA, store.lastOwner())
        assertEquals(1L, store.countUnreadIncoming())
        inbox.close()
    }

    @Test fun emptyDisconnectedLastOwnerDoesNotHideNonemptyHistoryAndNewOwnerKeepsExistingNumber() = runTest {
        val store = FakeMessageRepository()
        store.insertIncomingIfAbsent(ownerB, "b", "b", "155501", "synthetic", 1, 1)
        var live = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = ownerA)
        lateinit var inbox: MessageInboxSelection
        inbox = MessageInboxSelection(store, this) { inbox.render(live) }
        inbox.refresh()
        runCurrent()
        assertEquals(ownerB, inbox.choices.value.selectedOwner)
        val number = inbox.choices.value.lines.single { it.owner == ownerB }.label?.ordinal
        val ownerC = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "c".repeat(43)))
        live = live.copy(ownerId = ownerC, composerEnabled = true)
        inbox.render(live)
        runCurrent()
        assertEquals(number, inbox.choices.value.lines.single { it.owner == ownerB }.label?.ordinal)
        inbox.select(ownerB)
        runCurrent()
        assertFalse((inbox.render(live) as MessageUiState.Inbox).composerEnabled)
        inbox.close()
    }
}
