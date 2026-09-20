package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureOperationalLogSourceTest {
    @Test
    fun exposesEveryOperationalLogState() {
        assertIs<OperationalLogUiState.Loading>(FixtureOperationalLogSource("loading").state.value)
        assertIs<OperationalLogUiState.Empty>(FixtureOperationalLogSource("empty").state.value)
        val ready = assertIs<OperationalLogUiState.Ready>(FixtureOperationalLogSource("ready").state.value)
        assertTrue(ready.files.size >= 2)
        assertTrue(ready.entries.size >= 2)
        assertEquals("f1", ready.selectedFileId)
        assertEquals(ready.entries, ready.filteredEntries)
        val warning = assertIs<OperationalLogUiState.Warning>(FixtureOperationalLogSource("warning").state.value)
        assertTrue(warning.truncated)
        assertTrue(warning.issues.isNotEmpty())
        assertEquals(2, warning.files.size)
        assertIs<OperationalLogUiState.Failure>(FixtureOperationalLogSource("failure").state.value)
        assertIs<OperationalLogUiState.PermissionDenied>(FixtureOperationalLogSource("permission").state.value)
        assertIs<OperationalLogUiState.Unavailable>(FixtureOperationalLogSource("unavailable").state.value)
    }

    @Test
    fun filtersReadyEntriesLocallyWithoutChangingCatalog() {
        val source = FixtureOperationalLogSource("ready")
        val initial = assertIs<OperationalLogUiState.Ready>(source.state.value)

        source.setKindFilter(OperationalLogKind.TUNNEL)
        source.setLevelFilter(OperationalLogLevel.WARN)
        source.setInstanceFilter("实例 •••• 1234")

        val filtered = assertIs<OperationalLogUiState.Ready>(source.state.value)
        assertEquals(initial.files, filtered.files)
        assertEquals(initial.selectedFileId, filtered.selectedFileId)
        assertEquals(initial.entries, filtered.entries)
        assertEquals(1, filtered.filteredEntries.size)
        assertEquals(OperationalLogKind.TUNNEL, filtered.filteredEntries.single().kind)
        assertEquals(OperationalLogLevel.WARN, filtered.filteredEntries.single().level)
        assertEquals("实例 •••• 1234", filtered.filteredEntries.single().displayLabel)
    }

    @Test
    fun selectFileSwitchesEntriesAndKeepsCurrentFilter() {
        val source = FixtureOperationalLogSource("ready")
        source.setKindFilter(OperationalLogKind.ENGINE)
        source.selectFile("f2")

        val selected = assertIs<OperationalLogUiState.Ready>(source.state.value)
        assertEquals("f2", selected.selectedFileId)
        assertTrue(selected.entries.all { it.kind == OperationalLogKind.ENGINE })
        assertTrue(selected.entries.all { it.displayLabel == "实例 •••• 5678" })
        assertEquals(selected.entries, selected.filteredEntries)
        assertEquals(OperationalLogKind.ENGINE, selected.filter.kind)
    }

    @Test
    fun refreshRecoversToReadyFromEveryMode() {
        listOf("loading", "empty", "warning", "failure", "permission", "unavailable").forEach { mode ->
            val source = FixtureOperationalLogSource(mode)
            source.refresh()
            val ready = assertIs<OperationalLogUiState.Ready>(source.state.value)
            assertEquals("f1", ready.selectedFileId)
            assertTrue(ready.files.isNotEmpty())
            assertTrue(ready.entries.isNotEmpty())
        }
    }

    @Test
    fun refreshPreservesSelectedFileAfterRecovery() {
        val source = FixtureOperationalLogSource("failure")
        source.selectFile("f2")
        source.refresh()
        assertEquals("f2", assertIs<OperationalLogUiState.Ready>(source.state.value).selectedFileId)
    }
}
