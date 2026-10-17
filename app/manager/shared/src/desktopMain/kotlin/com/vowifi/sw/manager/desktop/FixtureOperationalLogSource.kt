package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.OperationalLogEvaluator
import com.vowifi.sw.manager.data.OperationalLogSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.OperationalLogCatalogView
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogParseIssue
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FixtureOperationalLogSource(mode: String) : OperationalLogSource {
    private var selectedFileId: String? = null
    private var filter = OperationalLogFilter()
    private val mutableState = MutableStateFlow(fixture(mode))
    override val state: StateFlow<OperationalLogUiState> = mutableState.asStateFlow()
    private var closed = false

    override fun refresh() {
        refreshSnapshot()
    }

    override suspend fun refreshAndAwait(): RefreshResult = refreshSnapshot()

    private fun refreshSnapshot(): RefreshResult {
        if (closed) return RefreshResult.Unsupported
        mutableState.value = present(readyEntries())
        return RefreshResult.Completed
    }

    override fun close() { closed = true }

    override fun selectFile(fileId: String) {
        if (closed || entriesById[fileId] == null) return
        selectedFileId = fileId
        mutableState.value = when (mutableState.value) {
            is OperationalLogUiState.Warning -> presentWarning(entriesById.getValue(fileId))
            is OperationalLogUiState.Ready -> present(entriesById.getValue(fileId))
            else -> mutableState.value
        }
    }

    override fun setKindFilter(kind: OperationalLogKind?) = applyFilter(filter.copy(kind = kind))

    override fun setLevelFilter(level: OperationalLogLevel?) = applyFilter(filter.copy(level = level))

    override fun setInstanceFilter(displayLabel: String?) = applyFilter(filter.copy(displayLabel = displayLabel))

    private fun applyFilter(next: OperationalLogFilter) {
        if (closed) return
        filter = next
        mutableState.value = OperationalLogEvaluator.withFilter(mutableState.value, filter)
    }

    private fun fixture(mode: String): OperationalLogUiState = when (mode) {
        "loading" -> OperationalLogUiState.Loading
        "empty" -> OperationalLogUiState.Empty
        "warning" -> presentWarning(readyEntries())
        "failure" -> OperationalLogEvaluator.failure("样例日志结果无法解析")
        "permission" -> OperationalLogEvaluator.permissionDenied("KernelSU 尚未授权")
        "unavailable" -> OperationalLogEvaluator.unavailable("当前模块不支持运行日志")
        else -> present(readyEntries())
    }

    private fun present(entries: List<OperationalLogEntry>): OperationalLogUiState {
        val selected = OperationalLogEvaluator.selectedFileId(FILES, selectedFileId) ?: return OperationalLogUiState.Empty
        selectedFileId = selected
        return OperationalLogEvaluator.withCatalog(
            OperationalLogUiState.Ready(entries),
            OperationalLogCatalogView(FILES, selected, filter),
        )
    }

    private fun presentWarning(entries: List<OperationalLogEntry>): OperationalLogUiState {
        val selected = OperationalLogEvaluator.selectedFileId(FILES, selectedFileId) ?: return OperationalLogUiState.Empty
        selectedFileId = selected
        return OperationalLogEvaluator.withCatalog(
            OperationalLogUiState.Warning(entries, truncated = true, issues = listOf(OperationalLogParseIssue(2))),
            OperationalLogCatalogView(FILES, selected, filter),
        )
    }

    private fun readyEntries(): List<OperationalLogEntry> =
        entriesById[selectedFileId] ?: entriesById.getValue(FILES.first().id)

    private companion object {
        val FILES = listOf(
            OperationalLogFile("f1", "engine.log", "1234"),
            OperationalLogFile("f2", "launcher.log", "5678"),
        )
        val FILE_ONE = listOf(
            OperationalLogEntry(
                "2026-08-17 12:34:56.789",
                OperationalLogLevel.WARN,
                OperationalLogKind.TUNNEL,
                "实例 •••• 1234",
                OperationalLogMessage.TUNNEL_DISCONNECTED,
                "tunnel disconnected",
            ),
            OperationalLogEntry(
                "2026-08-17 12:34:57.000",
                OperationalLogLevel.INFO,
                OperationalLogKind.IMS,
                "实例 •••• 5678",
                OperationalLogMessage.IMS_REGISTERED,
                "ims registered",
            ),
            OperationalLogEntry(
                "2026-08-17 12:34:58.000",
                OperationalLogLevel.ERROR,
                OperationalLogKind.ENGINE,
                "实例 •••• 1234",
                OperationalLogMessage.OPERATION_FAILED,
                "operation failed",
            ),
        )
        val FILE_TWO = listOf(
            OperationalLogEntry(
                "2026-08-17 12:35:00.000",
                OperationalLogLevel.INFO,
                OperationalLogKind.ENGINE,
                "实例 •••• 5678",
                OperationalLogMessage.ENGINE_STARTED,
                "engine started",
            ),
            OperationalLogEntry(
                "2026-08-17 12:35:01.000",
                OperationalLogLevel.WARN,
                OperationalLogKind.ENGINE,
                "实例 •••• 5678",
                OperationalLogMessage.NEEDS_ATTENTION,
                "needs attention",
            ),
        )
        val entriesById = mapOf("f1" to FILE_ONE, "f2" to FILE_TWO)
    }
}
