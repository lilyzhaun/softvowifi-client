@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import kotlin.test.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class UnifiedCallLineHistoryTest {
    @Test fun explicitOutgoingSelectionReplacesPinnedHistoryEvenForEmptyOwner() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext)
        try {
            fixture.repository.upsert(OWNER_B, historyWrite())
            fixture.source.selectLine(OWNER_B)
            fixture.source.selectHistoryOwner(fixture.source.historyOwners.value.single { it.owner == OWNER_B })
            assertIs<CallHistoryState.Ready>(fixture.source.history.value)
            fixture.source.selectLine(OWNER_A)
            assertEquals(OWNER_A, fixture.source.lines.value.selectedOwner)
            assertEquals(OWNER_A, fixture.repository.lastOwner())
            assertIs<CallHistoryState.Empty>(fixture.source.history.value)
        } finally { fixture.source.close() }
    }

    @Test fun runningFlagsComeFromEnginesNotActiveSimRoster() = runTest(UnconfinedTestDispatcher()) {
        val fixture = CallAggregateFixture(coroutineContext, onlyFirst = true)
        try {
            val choices = fixture.source.lines.value.choices
            assertTrue(choices.single { it.owner == OWNER_A }.running)
            assertFalse(choices.single { it.owner == OWNER_B }.running)
            fixture.source.selectLine(OWNER_B)
            assertEquals(OWNER_A, fixture.source.lines.value.selectedOwner)
        } finally { fixture.source.close() }
    }
}
