package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.data.UnsupportedCallNotificationSettingsSource
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class S3AuxiliaryFixtureRefreshCompletionTest {
    @Test
    fun diagnosticsFixturePublishesItsActualRecoveryAndSameValueBeforeCompletion() = runBlocking {
        val source = FixtureEnvironmentDiagnosticsSource("failure")
        assertEquals(RefreshResult.Completed, source.refreshAndAwait())
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(source.state.value)
        assertEquals(RefreshResult.Completed, source.refreshAndAwait())
        assertEquals(ready, source.state.value)
        source.close()
        assertEquals(RefreshResult.Unsupported, source.refreshAndAwait())
        assertEquals(ready, source.state.value)
    }

    @Test
    fun logFixtureCompletesItsRecoveryAndRetainsTheSelectedFileAndFilter() = runBlocking {
        val source = FixtureOperationalLogSource("failure")
        source.setKindFilter(OperationalLogKind.ENGINE)
        source.selectFile("f2")
        assertEquals(RefreshResult.Completed, source.refreshAndAwait())
        val ready = assertIs<OperationalLogUiState.Ready>(source.state.value)
        assertEquals("f2", ready.selectedFileId)
        assertEquals(OperationalLogKind.ENGINE, ready.filter.kind)
        assertEquals(ready.entries, ready.filteredEntries)
        assertEquals(RefreshResult.Completed, source.refreshAndAwait())
        assertEquals(ready, source.state.value)
        source.close()
        assertEquals(RefreshResult.Unsupported, source.refreshAndAwait())
        assertEquals(ready, source.state.value)
    }

    @Test
    fun unsupportedNotificationSourceNeverClaimsACompletedRead() = runBlocking {
        val source = UnsupportedCallNotificationSettingsSource
        val before = source.state.value
        source.refresh()
        assertEquals(RefreshResult.Unsupported, source.refreshAndAwait())
        assertEquals(before, source.state.value)
    }
}
