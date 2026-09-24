@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageStartupRosterRegressionTest {
    @Test fun partialRosterOffersKnownOfflineOwnerWithoutHistoryOrAutomaticSelection() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.instances = emptyList(); rig.active = listOf("8986001", null); rig.start()
            val choice = rig.source.historyChoices.value.lines.single()
            assertEquals(OWNER_A, choice.owner)
            assertEquals(true, choice.active)
            assertFalse(choice.connected); assertFalse(choice.sendable)
            assertFalse(rig.source.historyChoices.value.activeRosterComplete)
            assertNull(rig.source.historyChoices.value.selectedOwner)
            rig.source.sendSms("155501", "unbound"); runCurrent()
            assertTrue(rig.wireA.sends.isEmpty())
        } finally { rig.close() }
    }

    @Test fun knownPresentIsTrueButAbsentIsOnlyFalseForACompleteRoster() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.repository.insertQueued(OWNER_B, "orphan-b", "155501", "synthetic", 1)
            rig.instances = emptyList(); rig.active = listOf("8986001", null); rig.start()
            val partial = rig.source.historyChoices.value.lines.associateBy { it.owner }
            assertEquals(true, partial[OWNER_A]?.active)
            assertNull(partial[OWNER_B]?.active)
            rig.active = listOf("8986001"); rig.refresh()
            assertTrue(rig.source.historyChoices.value.activeRosterComplete)
            assertEquals(false, rig.source.historyChoices.value.lines.single { it.owner == OWNER_B }.active)
            rig.active = null; rig.refresh()
            assertNull(rig.source.historyChoices.value.lines.single { it.owner == OWNER_B }.active)
        } finally { rig.close() }
    }

    @Test fun startupRecoversEveryHistoricalOwnerBeforeSendsAndRefreshDoesNotInterruptNewQueuedMessages() = runTest {
        val stored = FakeMessageRepository()
        stored.insertQueued(OWNER_A, "orphan-a", "155501", "synthetic", 1)
        stored.insertQueued(OWNER_B, "orphan-b", "155501", "synthetic", 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<SimOwnerId>()
        val repository = object : MessageRepository by stored {
            override suspend fun interruptLeftoverQueued(ownerId: SimOwnerId, nowMs: Long) {
                calls += ownerId
                if (ownerId == OWNER_B) { entered.complete(Unit); release.await() }
                stored.interruptLeftoverQueued(ownerId, nowMs)
            }
        }
        val wire = DualSmsWire()
        val source = EngineMessageSource(
            EngineMessageRuntime({ listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, 1)) },
                { wire }, { 100 }, { listOf("8986001") }),
            coroutineContext, repository, ::ownerFor, sequentialIds(),
        )
        try {
            runCurrent()
            assertTrue(entered.isCompleted, "startup must visit offline owner B before attaching A")
            source.sendSms("155501", "before recovery"); runCurrent()
            assertTrue(wire.sends.isEmpty())
            release.complete(Unit); runCurrent()
            assertEquals(OutgoingMessageStatus.UNKNOWN, (stored.listByOwner(OWNER_A).single() as MessageRecord.Outgoing).status)
            assertEquals(OutgoingMessageStatus.UNKNOWN, (stored.listByOwner(OWNER_B).single() as MessageRecord.Outgoing).status)
            assertEquals(mapOf(OWNER_A to 1, OWNER_B to 1), calls.groupingBy { it }.eachCount())
            wire.gate = CompletableDeferred()
            source.sendSms("155501", "new pending"); runCurrent()
            source.watchAttachment(); advanceTimeBy(6_001); runCurrent()
            source.refresh(); runCurrent(); source.refresh(); runCurrent()
            assertEquals(OutgoingMessageStatus.QUEUED, (stored.listByOwner(OWNER_A).last() as MessageRecord.Outgoing).status)
            assertEquals(mapOf(OWNER_A to 1, OWNER_B to 1), calls.groupingBy { it }.eachCount())
            assertEquals(1, wire.sends.size)
        } finally { release.complete(Unit); source.close(); runCurrent() }
    }
}
