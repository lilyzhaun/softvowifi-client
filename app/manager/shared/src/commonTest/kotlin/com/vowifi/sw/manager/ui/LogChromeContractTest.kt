package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.OperationalLogFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Filter

class LogChromeContractTest {
    @Test
    fun settingsLogsUsesCompactMiuixChromeWithOneEmbeddedScrollOwner() {
        val contract = logsChromeContract()

        assertEquals(LogFilterControlPresentation.OVERLAY_DROPDOWN, contract.fileControl)
        assertEquals(LogFilterControlPresentation.OVERLAY_DROPDOWN, contract.kindControl)
        assertEquals(LogFilterControlPresentation.OVERLAY_DROPDOWN, contract.levelControl)
        assertEquals(1, contract.verticalScrollOwnerCount)
        assertFalse(contract.embeddedNestedScroll)
        assertEquals(LogEntrySurface.GROUPED_ROWS, contract.entrySurface)
        assertEquals(ManagerPressStyle.CardRow, contract.entryPressStyle)
        assertEquals(MiuixIcons.File, contract.fileIcon)
        assertEquals(MiuixIcons.Filter, contract.kindIcon)
        assertEquals(MiuixIcons.Filter, contract.levelIcon)
        assertFalse(contract.usesDefaultSelectableIndication)
        assertFalse(contract.usesBlueFilterUnderlines)
        assertEquals(12, contract.cardGapDp)
        assertEquals(0, contract.entryGapDp)
        assertEquals(1, contract.privacyNarrativeCount)
        assertEquals(2, contract.entryBodyMaxLines)
    }

    @Test
    fun operationalLogDataSectionsFollowDeterministicOrder() {
        assertEquals(
            listOf(
                OperationalLogDataSection.FILTERS,
                OperationalLogDataSection.EXPORT,
                OperationalLogDataSection.EVENTS,
            ),
            operationalLogDataSectionOrder(hasWarning = false),
        )
        assertEquals(
            listOf(
                OperationalLogDataSection.WARNING,
                OperationalLogDataSection.FILTERS,
                OperationalLogDataSection.EXPORT,
                OperationalLogDataSection.EVENTS,
            ),
            operationalLogDataSectionOrder(hasWarning = true),
        )
    }

    @Test
    fun fileSelectionClearsTheInstanceBeforeSelectingTheFile() {
        val calls = mutableListOf<String>()
        val selection = LogFileSelection(
            setInstance = { calls += "instance:${it ?: "none"}" },
            selectFile = { calls += "file:$it" },
        )

        selection.select(OperationalLogFile("private-id", "engine.log", "1234"))

        assertEquals(listOf("instance:none", "file:private-id"), calls)
    }

    @Test
    fun exportIsAvailableOnlyForFilteredEntriesAndBusyStateIsHonest() {
        assertFalse(logExportPresentation(hasFilteredEntries = false, busy = false).enabled)
        assertTrue(logExportPresentation(hasFilteredEntries = true, busy = false).enabled)
        assertEquals("导出日志", logExportPresentation(hasFilteredEntries = true, busy = false).label)

        val busy = logExportPresentation(hasFilteredEntries = true, busy = true)
        assertFalse(busy.enabled)
        assertEquals("正在准备日志", busy.label)
        assertEquals("导出前会自动隐藏号码、密钥、地址、路径和短信内容。", logPrivacyNote())
    }
}
