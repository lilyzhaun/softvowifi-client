package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.OperationalLogFile

internal enum class LogFilterControlPresentation {
    OVERLAY_DROPDOWN,
}

internal enum class LogEntrySurface {
    COMPACT_CARD,
}

internal enum class OperationalLogDataSection {
    WARNING,
    FILTERS,
    EVENTS,
    EXPORT,
}

internal fun operationalLogDataSectionOrder(hasWarning: Boolean): List<OperationalLogDataSection> =
    if (hasWarning) {
        listOf(
            OperationalLogDataSection.WARNING,
            OperationalLogDataSection.FILTERS,
            OperationalLogDataSection.EVENTS,
            OperationalLogDataSection.EXPORT,
        )
    } else {
        listOf(
            OperationalLogDataSection.FILTERS,
            OperationalLogDataSection.EVENTS,
            OperationalLogDataSection.EXPORT,
        )
    }

internal data class LogsChromeContract(
    val fileControl: LogFilterControlPresentation,
    val kindControl: LogFilterControlPresentation,
    val levelControl: LogFilterControlPresentation,
    val verticalScrollOwnerCount: Int,
    val embeddedNestedScroll: Boolean,
    val entrySurface: LogEntrySurface,
    val usesDefaultSelectableIndication: Boolean,
    val usesBlueFilterUnderlines: Boolean,
    val cardGapDp: Int = homeLayoutContract.cardGapDp,
    val entryGapDp: Int = homeLayoutContract.cardGapDp,
    val privacyNarrativeCount: Int = 1,
    val entryBodyMaxLines: Int = 2,
)

internal fun logsChromeContract() = LogsChromeContract(
    fileControl = LogFilterControlPresentation.OVERLAY_DROPDOWN,
    kindControl = LogFilterControlPresentation.OVERLAY_DROPDOWN,
    levelControl = LogFilterControlPresentation.OVERLAY_DROPDOWN,
    verticalScrollOwnerCount = 1,
    embeddedNestedScroll = false,
    entrySurface = LogEntrySurface.COMPACT_CARD,
    usesDefaultSelectableIndication = false,
    usesBlueFilterUnderlines = false,
    cardGapDp = homeLayoutContract.cardGapDp,
    entryGapDp = homeLayoutContract.cardGapDp,
    privacyNarrativeCount = 1,
    entryBodyMaxLines = 2,
)

internal fun logPrivacyNote(): String = "导出前会自动隐藏号码、密钥、地址、路径和短信内容。"

internal class LogFileSelection(
    private val setInstance: (String?) -> Unit,
    private val selectFile: (String) -> Unit,
) {
    fun select(file: OperationalLogFile) {
        setInstance(null)
        selectFile(file.id)
    }
}

internal data class LogExportPresentation(
    val enabled: Boolean,
    val busy: Boolean,
    val label: String,
)

internal fun logExportPresentation(hasFilteredEntries: Boolean, busy: Boolean): LogExportPresentation =
    LogExportPresentation(
        enabled = hasFilteredEntries && !busy,
        busy = busy,
        label = if (busy) "正在准备日志" else "导出日志",
    )
