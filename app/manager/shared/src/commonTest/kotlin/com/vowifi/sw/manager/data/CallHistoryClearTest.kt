@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallHistoryClearTest {
    @Test
    fun clearRemovesOnlyDisplayedOwnerWhenCallHasEnded() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val repository = FakeCallHistoryRepository()
        val source = historySource(transport, repository)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        transport.emitIncoming(CALL_TOKEN, "ENDED", reason = "rejected")
        val displayed = assertIs<CallHistoryState.Ready>(source.history.value)

        source.clearHistory(displayed)

        assertIs<CallHistoryState.Empty>(source.history.value)
        assertEquals(0, repository.listByOwner(OWNER_A).size)
        source.close()
    }

    @Test
    fun clearDoesNotRemoveAnActiveCall() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val repository = FakeCallHistoryRepository()
        val source = historySource(transport, repository)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        val displayed = assertIs<CallHistoryState.Ready>(source.history.value)

        source.clearHistory(displayed)

        assertEquals(1, repository.listByOwner(OWNER_A).size)
        source.close()
    }

    @Test
    fun staleConfirmationCannotClearNewHistory() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val repository = FakeCallHistoryRepository()
        val source = historySource(transport, repository)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        val stale = assertIs<CallHistoryState.Ready>(source.history.value)
        transport.emitIncoming(CALL_TOKEN, "ENDED", reason = "rejected")

        source.clearHistory(stale)

        assertEquals(1, repository.listByOwner(OWNER_A).size)
        source.close()
    }
}
