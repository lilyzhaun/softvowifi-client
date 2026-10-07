@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUiState
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RegisteredLineDefaultTest {
    @Test fun messagesChooseOnlyRegisteredLineThenFirstSlotWhenBothRegister() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.wireA.ims = "UNREGISTERED"
            rig.start()
            assertEquals(OWNER_B, rig.source.historyChoices.value.selectedOwner)
            assertTrue(assertIs<MessageUiState.Inbox>(rig.source.state.value).composerEnabled)
            rig.wireA.ims = "REGISTERED"
            rig.refresh()
            assertEquals(OWNER_A, rig.source.historyChoices.value.selectedOwner)
            assertTrue(assertIs<MessageUiState.Inbox>(rig.source.state.value).composerEnabled)
        } finally { rig.close() }
    }

    @Test fun messagesDefaultDoesNotOverrideExplicitHistorySelection() = runTest {
        val rig = DualSmsFixture(this)
        try {
            rig.wireA.ims = "UNREGISTERED"
            rig.start()
            assertEquals(OWNER_B, rig.source.historyChoices.value.selectedOwner)
            rig.select(OWNER_A)
            rig.refresh()
            assertEquals(OWNER_A, rig.source.historyChoices.value.selectedOwner)
            assertFalse(assertIs<MessageUiState.Inbox>(rig.source.state.value).composerEnabled)
        } finally { rig.close() }
    }

    @Test fun callsChooseRegisteredLineWithAnotherActiveSimStopped() = runTest(UnconfinedTestDispatcher()) {
        val rig = CallAggregateFixture(coroutineContext, onlyFirst = true)
        try {
            assertEquals(OWNER_A, rig.source.lines.value.selectedOwner)
            assertNotNull(rig.source.lines.value.outgoingBinding)
        } finally { rig.source.close() }
    }

    @Test fun callsChooseFirstSlotWhenBothRegister() = runTest(UnconfinedTestDispatcher()) {
        val rig = CallAggregateFixture(coroutineContext)
        try {
            assertEquals(OWNER_A, rig.source.lines.value.selectedOwner)
            assertNotNull(rig.source.lines.value.outgoingBinding)
            assertEquals(OWNER_A, rig.repository.lastOwner())
        } finally { rig.source.close() }
    }

    @Test fun callsChooseSecondWhenFirstStopsAndRespectRosterOrder() = runTest(UnconfinedTestDispatcher()) {
        val rig = CallAggregateFixture(coroutineContext)
        try {
            rig.instances = rig.instances.drop(1)
            rig.source.refresh()
            assertEquals(OWNER_B, rig.source.lines.value.selectedOwner)
            assertNotNull(rig.source.lines.value.outgoingBinding)
            assertEquals(OWNER_B, rig.repository.lastOwner())
            rig.instances = listOf(callRunning(CallAggregateFixture.ICCID_A), callRunning(CallAggregateFixture.ICCID_B))
            rig.roster = listOf(CallAggregateFixture.ICCID_B, CallAggregateFixture.ICCID_A)
            rig.source.refresh()
            assertEquals(OWNER_B, rig.source.lines.value.selectedOwner)
        } finally { rig.source.close() }
    }

    @Test fun automaticRefreshKeepsHistoryButExplicitOutgoingSelectionUpdatesIt() = runTest(UnconfinedTestDispatcher()) {
        val rig = CallAggregateFixture(coroutineContext)
        try {
            rig.repository.upsert(OWNER_A, historyWrite())
            rig.repository.upsert(OWNER_B, historyWrite())
            rig.source.selectLine(OWNER_B)
            rig.source.selectHistoryOwner(rig.source.historyOwners.value.single { it.owner == OWNER_A })
            assertEquals(OWNER_A, rig.repository.lastOwner())
            rig.source.refresh()
            assertEquals(OWNER_A, rig.repository.lastOwner())
            rig.source.selectLine(OWNER_A)
            rig.source.selectLine(OWNER_B)
            assertEquals(OWNER_B, rig.source.lines.value.selectedOwner)
            assertEquals(OWNER_B, rig.repository.lastOwner())
        } finally { rig.source.close() }
    }
}
