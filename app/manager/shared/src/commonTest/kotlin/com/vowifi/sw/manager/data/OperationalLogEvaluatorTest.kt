package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogCatalogView
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperationalLogEvaluatorTest {
    @Test
    fun mapsCatalogAndSourceOutcomesToDistinctUiStates() {
        assertIs<OperationalLogUiState.Loading>(OperationalLogUiState.Loading)
        assertIs<OperationalLogUiState.Empty>(OperationalLogEvaluator.fromTail(emptyTail()))
        assertIs<OperationalLogUiState.Ready>(OperationalLogEvaluator.fromTail(readyTail()))
        assertIs<OperationalLogUiState.Warning>(OperationalLogEvaluator.fromTail(readyTail(truncated = true)))
        assertIs<OperationalLogUiState.Warning>(OperationalLogEvaluator.fromTail(tailWithBrokenLine()))
        assertIs<OperationalLogUiState.Failure>(OperationalLogEvaluator.failure())
        assertIs<OperationalLogUiState.PermissionDenied>(OperationalLogEvaluator.permissionDenied())
        assertIs<OperationalLogUiState.Unavailable>(OperationalLogEvaluator.unavailable())
    }

    @Test
    fun filtersReadyEntriesByKindLevelAndMaskedInstance() {
        val ready = assertIs<OperationalLogUiState.Ready>(OperationalLogEvaluator.fromTail(readyTail()))

        val tunnelWarnings = OperationalLogEvaluator.filter(
            ready.entries,
            OperationalLogFilter(
                kind = OperationalLogKind.TUNNEL,
                level = OperationalLogLevel.WARN,
                displayLabel = "实例 •••• 1234",
            ),
        )

        assertEquals(1, tunnelWarnings.size)
        assertEquals(OperationalLogKind.TUNNEL, tunnelWarnings.single().kind)
        assertEquals(OperationalLogLevel.WARN, tunnelWarnings.single().level)
        assertEquals("实例 •••• 1234", tunnelWarnings.single().displayLabel)
    }

    @Test
    fun keepsDefaultCatalogFieldsOnExistingFromTailConstructors() {
        val ready = assertIs<OperationalLogUiState.Ready>(OperationalLogEvaluator.fromTail(readyTail()))
        assertEquals(emptyList(), ready.files)
        assertEquals("", ready.selectedFileId)
        assertEquals(OperationalLogFilter(), ready.filter)
        assertEquals(ready.entries, ready.filteredEntries)

        val warning = assertIs<OperationalLogUiState.Warning>(
            OperationalLogEvaluator.fromTail(readyTail(truncated = true)),
        )
        assertEquals(emptyList(), warning.files)
        assertEquals(warning.entries, warning.filteredEntries)
    }

    @Test
    fun mergesCatalogAndLocalFilterIntoReadyAndWarning() {
        val files = listOf(
            OperationalLogFile("f1", "engine.log", "1234"),
            OperationalLogFile("f2", "launcher.log", "5678"),
        )
        val filter = OperationalLogFilter(kind = OperationalLogKind.TUNNEL)
        val catalog = OperationalLogCatalogView(files, "f1", filter)

        val ready = assertIs<OperationalLogUiState.Ready>(
            OperationalLogEvaluator.withCatalog(OperationalLogEvaluator.fromTail(readyTail()), catalog),
        )
        assertEquals(files, ready.files)
        assertEquals("f1", ready.selectedFileId)
        assertEquals(filter, ready.filter)
        assertEquals(1, ready.filteredEntries.size)
        assertEquals(OperationalLogKind.TUNNEL, ready.filteredEntries.single().kind)

        val warning = assertIs<OperationalLogUiState.Warning>(
            OperationalLogEvaluator.withCatalog(
                OperationalLogEvaluator.fromTail(readyTail(truncated = true)),
                catalog,
            ),
        )
        assertEquals(files, warning.files)
        assertEquals("f1", warning.selectedFileId)
        assertEquals(1, warning.filteredEntries.size)

        assertIs<OperationalLogUiState.Empty>(
            OperationalLogEvaluator.withCatalog(
                ready,
                OperationalLogCatalogView(emptyList(), "f1"),
            ),
        )
        assertIs<OperationalLogUiState.Failure>(
            OperationalLogEvaluator.withCatalog(OperationalLogEvaluator.failure(), catalog),
        )
    }

    @Test
    fun reappliesFilterWithoutDroppingCatalogSelection() {
        val ready = assertIs<OperationalLogUiState.Ready>(
            OperationalLogEvaluator.withCatalog(
                OperationalLogEvaluator.fromTail(readyTail()),
                OperationalLogCatalogView(
                    listOf(OperationalLogFile("f2", "engine.log", "1234")),
                    "f2",
                ),
            ),
        )

        val filtered = assertIs<OperationalLogUiState.Ready>(
            OperationalLogEvaluator.withFilter(
                ready,
                OperationalLogFilter(level = OperationalLogLevel.INFO),
            ),
        )
        assertEquals("f2", filtered.selectedFileId)
        assertEquals(ready.files, filtered.files)
        assertEquals(ready.entries, filtered.entries)
        assertEquals(1, filtered.filteredEntries.size)
        assertEquals(OperationalLogLevel.INFO, filtered.filteredEntries.single().level)
    }

    @Test
    fun prefersExistingSelectedFileOtherwiseNewestFirst() {
        val files = listOf(
            OperationalLogFile("f3", "engine.log", "9999"),
            OperationalLogFile("f1", "launcher.log", "1234"),
        )
        assertEquals("f1", OperationalLogEvaluator.selectedFileId(files, "f1"))
        assertEquals("f3", OperationalLogEvaluator.selectedFileId(files, "f9"))
        assertEquals(null, OperationalLogEvaluator.selectedFileId(emptyList(), "f1"))
    }

    @Test
    fun exportsOnlyRedactedUtf8CatalogEntries() {
        val ready = assertIs<OperationalLogUiState.Ready>(OperationalLogEvaluator.fromTail(readyTail()))

        val exported = OperationalLogExporter.utf8(ready.entries)

        assertTrue(exported.startsWith("SoftVoWiFi 运行日志"))
        assertEquals("SoftVoWiFi 运行日志", OperationalLogExporter.utf8(emptyList()))
        assertEquals(exported, exported.encodeToByteArray().decodeToString())
        assertPrivate(exported)
    }
}

private fun emptyTail(): String = """{"ok":true,"id":"f1","truncated":false,"lines":[]}"""

private fun readyTail(truncated: Boolean = false): String =
    """{"ok":true,"id":"f1","truncated":$truncated,"lines":[""" +
        """"2026-08-17 12:34:56.789 [WARN] [tunnel] [•••• 1234] tunnel disconnected",""" +
        """"2026-08-17 12:34:57.000 [INFO] [ims] [•••• 5678] ims registered"]}"""

private fun tailWithBrokenLine(): String =
    """{"ok":true,"id":"f1","truncated":false,"lines":[""" +
        """"2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] engine started",""" +
        """"broken engine output"]}"""
