@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineMessageDualAvailabilityTest {
    @Test fun replacementGenerationRejectsLateRpcEvenWhenRuntimeReusesTheTransportObject() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start(); rig.select(OWNER_A)
            val oldGate = CompletableDeferred<Unit>(); rig.wireA.gate = oldGate
            rig.source.sendSms("155501", "old generation"); runCurrent()
            val original = rig.instances
            rig.instances = original.drop(1); rig.refresh()
            rig.instances = original; rig.refresh()
            val newGate = CompletableDeferred<Unit>(); rig.wireA.gate = newGate
            rig.source.sendSms("155501", "new generation"); runCurrent()
            oldGate.complete(Unit); runCurrent()
            assertTrue(assertIs<MessageUiState.Inbox>(rig.source.state.value).sendBusy)
            assertEquals(listOf(OutgoingMessageStatus.UNKNOWN, OutgoingMessageStatus.QUEUED),
                rig.repository.listByOwner(OWNER_A).filterIsInstance<MessageRecord.Outgoing>().map { it.status })
            newGate.complete(Unit); runCurrent()
            assertEquals(listOf(OutgoingMessageStatus.UNKNOWN, OutgoingMessageStatus.ACCEPTED),
                rig.repository.listByOwner(OWNER_A).filterIsInstance<MessageRecord.Outgoing>().map { it.status })
        } finally { rig.close() }
        assertEquals(0, rig.wireA.events.subscriptionCount.value)
        assertEquals(0, rig.wireB.events.subscriptionCount.value)
    }

    @Test fun twoActiveSimsWithOnlyOneRegisteredAutomaticallyBindThatLine() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.instances = rig.instances.take(1); rig.start()
            assertEquals(setOf(OWNER_A, OWNER_B), rig.source.historyChoices.value.lines.map { it.owner }.toSet())
            assertTrue(rig.source.historyChoices.value.lines.all { it.active == true })
            assertEquals(OWNER_A, rig.source.historyChoices.value.selectedOwner)
            rig.source.sendSms("155501", "registered default"); runCurrent()
            assertEquals(1, rig.wireA.sends.size)
            rig.select(OWNER_B)
            assertFalse(assertIs<MessageUiState.Inbox>(rig.source.state.value).composerEnabled)
            rig.source.sendSms("155501", "offline B"); runCurrent()
            assertEquals(1, rig.wireA.sends.size)
        } finally { rig.close() }
    }

    @Test fun registeredDefaultBindsWithDualOrSingleActiveInventory() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.repository.insertIncomingIfAbsent(OWNER_A, "stored", "stored", "155501", "synthetic", 1, 1)
            rig.repository.setLastOwner(OWNER_A); rig.start()
            assertTrue(assertIs<MessageUiState.Inbox>(rig.source.state.value).composerEnabled)
            assertEquals(OWNER_A, rig.source.historyChoices.value.selectedOwner)
            rig.instances = rig.instances.take(1); rig.active = listOf("8986001"); rig.refresh()
            assertTrue(assertIs<MessageUiState.Inbox>(rig.source.state.value).composerEnabled)
            rig.source.sendSms("155501", "bound single"); runCurrent()
            assertEquals(1, rig.wireA.sends.size)
        } finally { rig.close() }
    }

    @Test fun unknownOrPartlyUnreadableActiveInventoryCannotAutoBind() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.instances = rig.instances.take(1); rig.active = null; rig.start()
            rig.source.sendSms("155501", "unknown"); runCurrent()
            assertTrue(rig.wireA.sends.isEmpty())
            rig.active = listOf("8986001", null); rig.refresh()
            rig.source.sendSms("155501", "incomplete"); runCurrent()
            assertTrue(rig.wireA.sends.isEmpty())
            rig.select(OWNER_A); rig.source.sendSms("155501", "explicit"); runCurrent()
            assertEquals(1, rig.wireA.sends.size)
        } finally { rig.close() }
    }

    @Test fun registrationTransitionsKeepBothStreamsAndGateOnlyTheirOwnSends() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.wireB.ims = "REGISTERING"; rig.start(); rig.select(OWNER_B)
            val choice = rig.source.historyChoices.value.lines.single { it.owner == OWNER_B }
            assertTrue(choice.connected); assertFalse(choice.sendable)
            rig.source.sendSms("155501", "not registered"); runCurrent()
            assertTrue(rig.wireB.sends.isEmpty())
            rig.wireB.incoming(); rig.wireA.incoming(); runCurrent()
            assertEquals(1, rig.repository.listByOwner(OWNER_B).size)
            rig.wireB.ims = "REGISTERED"; rig.refresh()
            rig.source.sendSms("155501", "registered"); runCurrent()
            assertEquals(1, rig.wireB.sends.size)
            rig.wireB.ims = "UNREGISTERED"; rig.refresh()
            assertFalse(assertIs<MessageUiState.Inbox>(rig.source.state.value).composerEnabled)
            assertEquals(1, rig.wireA.events.subscriptionCount.value)
            assertEquals(1, rig.wireB.events.subscriptionCount.value)
        } finally { rig.close() }
    }

    @Test fun disconnectAndReconnectSameOwnerCannotAdoptOldRpcOrClearReplacementSend() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start(); rig.select(OWNER_A)
            val oldGate = CompletableDeferred<Unit>(); rig.wireA.gate = oldGate
            rig.source.sendSms("155501", "old"); runCurrent()
            rig.wireA.messageDisconnects.emit(Unit); runCurrent()
            assertEquals(0, rig.wireA.events.subscriptionCount.value)
            assertEquals(1, rig.wireB.events.subscriptionCount.value)
            rig.currentA = DualSmsWire()
            val newGate = CompletableDeferred<Unit>(); rig.currentA.gate = newGate
            rig.refresh(); rig.source.sendSms("155501", "new"); runCurrent()
            oldGate.complete(Unit); runCurrent()
            assertTrue(assertIs<MessageUiState.Inbox>(rig.source.state.value).sendBusy)
            rig.wireA.incoming("late"); rig.wireB.incoming(); runCurrent()
            newGate.complete(Unit); runCurrent()
            assertEquals(listOf(OutgoingMessageStatus.UNKNOWN, OutgoingMessageStatus.ACCEPTED),
                rig.repository.listByOwner(OWNER_A).filterIsInstance<MessageRecord.Outgoing>().map { it.status })
            assertEquals(1, rig.repository.listByOwner(OWNER_B).size)
            assertTrue(rig.repository.listByOwner(OWNER_A).none { it is MessageRecord.Incoming })
        } finally { rig.close() }
    }
}
