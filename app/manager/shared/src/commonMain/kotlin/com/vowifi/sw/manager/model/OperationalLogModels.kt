package com.vowifi.sw.manager.model

enum class OperationalLogKind {
    ENGINE,
    TUNNEL,
    IMS,
}

enum class OperationalLogLevel {
    INFO,
    WARN,
    ERROR,
}

enum class OperationalLogMessage {
    ENGINE_STARTED,
    ENGINE_STOPPED,
    TUNNEL_CONNECTING,
    TUNNEL_CONNECTED,
    TUNNEL_DISCONNECTED,
    IMS_REGISTERING,
    IMS_REGISTERED,
    IMS_UNREGISTERED,
    NEEDS_ATTENTION,
    OPERATION_FAILED,
    EVENT,
    UNAVAILABLE,
}

data class OperationalLogEntry(
    val timestamp: String,
    val level: OperationalLogLevel,
    val kind: OperationalLogKind,
    val displayLabel: String,
    val message: OperationalLogMessage,
    val safeSummary: String,
    val safeDetail: String = safeSummary,
    val detailTruncated: Boolean = false,
)

internal fun operationalLogSafeSummary(entry: OperationalLogEntry): String = entry.safeSummary

data class OperationalLogParseIssue(
    val lineNumber: Int,
    val message: String = "日志格式无法解析",
)

data class OperationalLogParseResult(
    val entries: List<OperationalLogEntry>,
    val issues: List<OperationalLogParseIssue>,
)

data class OperationalLogFile(
    val id: String,
    val name: String,
    val suffix: String,
)

data class OperationalLogList(
    val rotatedCount: Int,
    val files: List<OperationalLogFile>,
)

data class OperationalLogTail(
    val id: String,
    val truncated: Boolean,
    val lines: List<OperationalLogEntry>,
    val issues: List<OperationalLogParseIssue>,
)

data class OperationalLogFilter(
    val kind: OperationalLogKind? = null,
    val level: OperationalLogLevel? = null,
    val displayLabel: String? = null,
)

data class OperationalLogCatalogView(
    val files: List<OperationalLogFile>,
    val selectedFileId: String,
    val filter: OperationalLogFilter = OperationalLogFilter(),
)

sealed interface OperationalLogUiState {
    data object Loading : OperationalLogUiState
    data object Empty : OperationalLogUiState
    data class Ready(
        val entries: List<OperationalLogEntry>,
        val files: List<OperationalLogFile> = emptyList(),
        val selectedFileId: String = "",
        val filter: OperationalLogFilter = OperationalLogFilter(),
        val filteredEntries: List<OperationalLogEntry> = entries,
        val loadInfo: OperationalLogLoadInfo = OperationalLogLoadInfo(),
    ) : OperationalLogUiState
    data class Warning(
        val entries: List<OperationalLogEntry>,
        val truncated: Boolean,
        val issues: List<OperationalLogParseIssue>,
        val files: List<OperationalLogFile> = emptyList(),
        val selectedFileId: String = "",
        val filter: OperationalLogFilter = OperationalLogFilter(),
        val filteredEntries: List<OperationalLogEntry> = entries,
        val loadInfo: OperationalLogLoadInfo = OperationalLogLoadInfo(),
    ) : OperationalLogUiState
    data class Failure(val message: String) : OperationalLogUiState
    data class PermissionDenied(val message: String) : OperationalLogUiState
    data class Unavailable(val message: String) : OperationalLogUiState
}

class OperationalLogFormatException(message: String) : IllegalArgumentException(message)

data class OperationalLogLoadInfo(
    val reading: Boolean = false,
    val cached: Boolean = false,
    val legacy: Boolean = false,
    val issue: String? = null,
)
