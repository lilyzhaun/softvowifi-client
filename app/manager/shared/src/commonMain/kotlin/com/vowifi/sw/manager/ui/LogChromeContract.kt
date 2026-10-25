package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.OperationalLogFile
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Filter

internal enum class LogFilterControlPresentation {
    OVERLAY_DROPDOWN,
}

internal enum class LogEntrySurface {
    COMPACT_CARD,
    GROUPED_ROWS,
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
            OperationalLogDataSection.EXPORT,
            OperationalLogDataSection.EVENTS,
        )
    } else {
        listOf(
            OperationalLogDataSection.FILTERS,
            OperationalLogDataSection.EXPORT,
            OperationalLogDataSection.EVENTS,
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
    val entryGapDp: Int = 0,
    val privacyNarrativeCount: Int = 1,
    val entryBodyMaxLines: Int = 2,
    val entryPressStyle: ManagerPressStyle = ManagerPressStyle.CardRow,
    val fileIcon: ImageVector = MiuixIcons.File,
    val kindIcon: ImageVector = MiuixIcons.Filter,
    val levelIcon: ImageVector = MiuixIcons.Filter,
)

internal fun logsChromeContract() = LogsChromeContract(
    fileControl = LogFilterControlPresentation.OVERLAY_DROPDOWN,
    kindControl = LogFilterControlPresentation.OVERLAY_DROPDOWN,
    levelControl = LogFilterControlPresentation.OVERLAY_DROPDOWN,
    verticalScrollOwnerCount = 1,
    embeddedNestedScroll = false,
    entrySurface = LogEntrySurface.GROUPED_ROWS,
    usesDefaultSelectableIndication = false,
    usesBlueFilterUnderlines = false,
    cardGapDp = homeLayoutContract.cardGapDp,
    entryGapDp = 0,
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
