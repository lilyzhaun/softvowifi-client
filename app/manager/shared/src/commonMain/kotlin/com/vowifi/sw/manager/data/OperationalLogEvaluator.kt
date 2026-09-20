package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogCatalogView
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogFormatException
import com.vowifi.sw.manager.model.OperationalLogUiState

object OperationalLogEvaluator {
    fun fromTail(output: String): OperationalLogUiState = try {
        val tail = OperationalLogShellCodec.tail(output)
        when {
            tail.truncated || tail.issues.isNotEmpty() -> OperationalLogUiState.Warning(
                entries = tail.lines,
                truncated = tail.truncated,
                issues = tail.issues,
            )
            tail.lines.isEmpty() -> OperationalLogUiState.Empty
            else -> OperationalLogUiState.Ready(tail.lines)
        }
    } catch (_: OperationalLogFormatException) {
        failure("日志结果无法解析")
    }

    fun selectedFileId(
        files: List<OperationalLogFile>,
        selectedFileId: String?,
    ): String? = files.firstOrNull { it.id == selectedFileId }?.id ?: files.firstOrNull()?.id

    fun withCatalog(
        state: OperationalLogUiState,
        catalog: OperationalLogCatalogView,
    ): OperationalLogUiState = when (state) {
        OperationalLogUiState.Loading,
        is OperationalLogUiState.Failure,
        is OperationalLogUiState.PermissionDenied,
        is OperationalLogUiState.Unavailable -> state
        OperationalLogUiState.Empty -> if (catalog.files.isEmpty()) {
            OperationalLogUiState.Empty
        } else {
            ready(emptyList(), catalog)
        }
        is OperationalLogUiState.Ready -> if (catalog.files.isEmpty()) {
            OperationalLogUiState.Empty
        } else {
            ready(state.entries, catalog)
        }
        is OperationalLogUiState.Warning -> if (catalog.files.isEmpty()) {
            OperationalLogUiState.Empty
        } else {
            state.copy(
                files = catalog.files,
                selectedFileId = catalog.selectedFileId,
                filter = catalog.filter,
                filteredEntries = filter(state.entries, catalog.filter),
            )
        }
    }

    fun withFilter(
        state: OperationalLogUiState,
        filter: OperationalLogFilter,
    ): OperationalLogUiState = when (state) {
        is OperationalLogUiState.Ready -> state.copy(
            filter = filter,
            filteredEntries = filter(state.entries, filter),
        )
        is OperationalLogUiState.Warning -> state.copy(
            filter = filter,
            filteredEntries = filter(state.entries, filter),
        )
        OperationalLogUiState.Loading,
        OperationalLogUiState.Empty,
        is OperationalLogUiState.Failure,
        is OperationalLogUiState.PermissionDenied,
        is OperationalLogUiState.Unavailable -> state
    }

    private fun ready(
        entries: List<OperationalLogEntry>,
        catalog: OperationalLogCatalogView,
    ) = OperationalLogUiState.Ready(
        entries = entries,
        files = catalog.files,
        selectedFileId = catalog.selectedFileId,
        filter = catalog.filter,
        filteredEntries = filter(entries, catalog.filter),
    )

    fun filter(
        entries: List<OperationalLogEntry>,
        filter: OperationalLogFilter,
    ): List<OperationalLogEntry> = entries.filter { entry ->
        (filter.kind == null || entry.kind == filter.kind) &&
            (filter.level == null || entry.level == filter.level) &&
            (filter.displayLabel == null || entry.displayLabel == filter.displayLabel)
    }

    fun failure(message: String = "无法读取脱敏日志"): OperationalLogUiState.Failure =
        OperationalLogUiState.Failure(safeCopy(message, "无法读取脱敏日志"))

    fun permissionDenied(message: String = "当前权限无法读取脱敏日志"): OperationalLogUiState.PermissionDenied =
        OperationalLogUiState.PermissionDenied(safeCopy(message, "当前权限无法读取脱敏日志"))

    fun unavailable(message: String = "当前环境不提供运行日志"): OperationalLogUiState.Unavailable =
        OperationalLogUiState.Unavailable(safeCopy(message, "当前环境不提供运行日志"))

    private fun safeCopy(message: String, fallback: String): String =
        message.takeIf(OperationalLogRedactor::isPrivateUiText) ?: fallback
}
