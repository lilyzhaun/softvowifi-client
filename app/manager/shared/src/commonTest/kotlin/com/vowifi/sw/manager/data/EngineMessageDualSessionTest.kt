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

class EngineMessageDualSessionTest {
    @Test fun bothOwnersReceiveIdenticalIdsAndReplayOnlyOnceWhileViewingA() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start(); rig.select(OWNER_A)
            rig.wireA.incoming(); rig.wireB.incoming(); runCurrent()
            rig.wireA.incoming(); rig.wireB.incoming(); runCurrent()
            assertEquals(1, rig.repository.listByOwner(OWNER_A).size)
            assertEquals(1, rig.repository.listByOwner(OWNER_B).size)
            assertFalse(assertIs<MessageRecord.Incoming>(rig.repository.listByOwner(OWNER_B).single()).isRead)
            assertEquals(setOf(OWNER_A, OWNER_B), rig.snapshots.map { it.first }.toSet())
            assertEquals(2, rig.snapshots.size)
            assertEquals(OWNER_A, assertIs<MessageUiState.Inbox>(rig.source.state.value).ownerId)
        } finally { rig.close() }
    }

    @Test fun stoppingBOnlyUnsubscribesBAndAStillReceives() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start()
            assertEquals(1, rig.wireA.events.subscriptionCount.value)
            assertEquals(1, rig.wireB.events.subscriptionCount.value)
            rig.instances = rig.instances.take(1); rig.refresh()
            assertEquals(1, rig.wireA.events.subscriptionCount.value)
            assertEquals(0, rig.wireB.events.subscriptionCount.value)
            rig.wireA.incoming(); rig.wireB.incoming(); runCurrent()
            assertEquals(1, rig.repository.listByOwner(OWNER_A).size)
            assertTrue(rig.repository.listByOwner(OWNER_B).isEmpty())
        } finally { rig.close() }
        assertEquals(0, rig.wireA.events.subscriptionCount.value)
    }

    @Test fun reconnectARejectsOldWireEventsWithoutDetachingB() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start()
            val original = rig.instances
            rig.instances = original.drop(1); rig.refresh()
            rig.currentA = DualSmsWire()
            rig.instances = original; rig.refresh()
            rig.wireA.incoming("old"); rig.currentA.incoming("new"); rig.wireB.incoming(); runCurrent()
            assertEquals(listOf("new"), rig.repository.listByOwner(OWNER_A).map { it.engineSmsId })
            assertEquals(1, rig.repository.listByOwner(OWNER_B).size)
            assertEquals(1, rig.wireB.connects)
        } finally { rig.close() }
    }

    @Test fun liveBWithoutHistoryIsSelectableAndExplicitSendDoesNotUseA() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start()
            assertEquals(setOf(OWNER_A, OWNER_B), rig.source.historyChoices.value.lines.map { it.owner }.toSet())
            rig.select(OWNER_B)
            rig.source.sendFromDisplayedOwner(OWNER_B, "155501", "synthetic"); runCurrent()
            assertEquals(1, rig.wireB.sends.size)
            assertTrue(rig.wireA.sends.isEmpty())
            assertEquals(OutgoingMessageStatus.ACCEPTED, assertIs<MessageRecord.Outgoing>(rig.repository.listByOwner(OWNER_B).single()).status)
        } finally { rig.close() }
    }

    @Test fun conversationReplyUsesAWhileInboxSelectionIsB() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start(); rig.wireA.incoming(); runCurrent(); rig.select(OWNER_B)
            rig.source.selectConversation(StoredMessageConversation(OWNER_A, "155501").handle.value); runCurrent()
            rig.source.sendFromDisplayedOwner(OWNER_A, "155501", "synthetic reply"); runCurrent()
            assertEquals(1, rig.wireA.sends.size)
            assertTrue(rig.wireB.sends.isEmpty())
            assertEquals(OWNER_B, rig.source.historyChoices.value.selectedOwner)
        } finally { rig.close() }
    }

    @Test fun selectionChangeBeforeEnqueueAndDuringRpcDoesNotStealCompletion() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start(); rig.select(OWNER_A)
            val gate = CompletableDeferred<Unit>(); rig.wireA.gate = gate
            rig.source.sendFromDisplayedOwner(OWNER_A, "155501", "synthetic")
            rig.select(OWNER_B)
            assertEquals(1, rig.wireA.sends.size)
            rig.source.sendFromDisplayedOwner(OWNER_B, "155501", "synthetic B"); runCurrent()
            rig.wireB.delivered(); runCurrent()
            gate.complete(Unit); runCurrent()
            val displayed = assertIs<MessageUiState.Inbox>(rig.source.state.value)
            assertEquals(OWNER_B, displayed.ownerId)
            assertEquals(OWNER_B, displayed.sendAttempt?.owner)
            assertEquals(OutgoingMessageStatus.ACCEPTED, assertIs<MessageRecord.Outgoing>(rig.repository.listByOwner(OWNER_A).single()).status)
            assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, assertIs<MessageRecord.Outgoing>(rig.repository.listByOwner(OWNER_B).single()).status)
        } finally { rig.close() }
    }

    @Test fun stoppedSelectedBNeverFallsBackToAAndLateResultRemainsUnknown() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.start(); rig.select(OWNER_B)
            val gate = CompletableDeferred<Unit>(); rig.wireB.gate = gate
            rig.source.sendFromDisplayedOwner(OWNER_B, "155501", "synthetic"); runCurrent()
            rig.instances = rig.instances.take(1); rig.refresh()
            rig.source.sendSms("155501", "must not fall back"); runCurrent()
            gate.complete(Unit); runCurrent()
            assertTrue(rig.wireA.sends.isEmpty())
            assertEquals(1, rig.wireB.sends.size)
            assertEquals(OutgoingMessageStatus.UNKNOWN, assertIs<MessageRecord.Outgoing>(rig.repository.listByOwner(OWNER_B).single()).status)
            assertFalse(assertIs<MessageUiState.Inbox>(rig.source.state.value).composerEnabled)
        } finally { rig.close() }
    }

    @Test fun connectFailureOfBDoesNotStopAIngress() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.wireB.connectFailure = true; rig.start()
            rig.wireA.incoming(); runCurrent()
            assertEquals(1, rig.repository.listByOwner(OWNER_A).size)
            assertEquals(1, rig.wireA.events.subscriptionCount.value)
            assertTrue(rig.repository.listByOwner(OWNER_B).isEmpty())
        } finally { rig.close() }
    }
}
