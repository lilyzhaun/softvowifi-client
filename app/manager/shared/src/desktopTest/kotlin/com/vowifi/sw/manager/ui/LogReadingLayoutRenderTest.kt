@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogReadingLayoutRenderTest {
    @Test fun recordsAreVisibleBeforeOpeningFiltersOrExportOptions() = onMotionTestThread { runTest {
        val entry = OperationalLogEntry("2026-11-02 00:00:01", OperationalLogLevel.INFO, OperationalLogKind.ENGINE,
            "合成线路", OperationalLogMessage.ENGINE_STARTED, "已读到本次事件", "合成详情", headline = "引擎已启动")
        val file = OperationalLogFile("app-diagnostics-v1", "app.jsonl", "")
        val exports = mutableListOf<List<OperationalLogEntry>>()
        MotionTestEnvironment(testScheduler, 400, 800, durationScale = 0f) { s4SmsHost {
            SettingsLogsScreen(OperationalLogUiState.Ready(listOf(entry), listOf(file), file.id),
                SettingsLogsActions({}, {}, {}, {}, { exports += it }, fullDiagnosticExport = true), false)
        } }.use { e ->
            repeat(8) { e.advanceFrameBy(16) }
            assertTrue(s4Action(e, "引擎已启动").boundsInRoot.bottom < 600f, "首屏应直接读到日志正文")
            assertFalse(s4HasText(e, "导出时间范围"))
            assertFalse(s4HasText(e, "类型"))
            s4Tap(e, s4Action(e, "筛选").boundsInRoot.center)
            repeat(8) { e.advanceFrameBy(16) }
            assertTrue(s4HasText(e, "类型")); assertTrue(s4HasText(e, "级别"))
            s4Tap(e, s4Action(e, "完成").boundsInRoot.center)
            repeat(8) { e.advanceFrameBy(16) }
            s4Tap(e, s4Action(e, "导出日志").boundsInRoot.center)
            repeat(8) { e.advanceFrameBy(16) }
            assertTrue(s4HasText(e, "导出时间范围"))
            assertTrue(s4HasText(e, logPrivacyNote()))
            assertTrue(exports.isEmpty(), "打开导出选项不能提前触发写文件")
            s4Tap(e, s4Action(e, "选择保存位置").boundsInRoot.center)
            repeat(8) { e.advanceFrameBy(16) }
            assertEquals(listOf(listOf(entry)), exports)
        }
    } }

    @Test fun oneFailedReadHasOnlyOnePersistentErrorAndRetry() = onMotionTestThread { runTest {
        val file = OperationalLogFile("app-diagnostics-v1", "app.jsonl", "")
        val state = mutableStateOf<OperationalLogUiState>(OperationalLogUiState.Ready(emptyList(), listOf(file), file.id))
        MotionTestEnvironment(testScheduler, 400, 800, durationScale = 0f) { s4SmsHost {
            SettingsLogsScreen(state.value, SettingsLogsActions({}, {}, {}, {}, {}), false, onRefresh = {
                state.value = OperationalLogUiState.Ready(emptyList(), listOf(file), file.id,
                    loadInfo = OperationalLogLoadInfo(issue = "合成读取失败"))
                RefreshResult.Failed("合成读取失败")
            })
        } }.use { e ->
            repeat(8) { e.advanceFrameBy(16) }
            s4Tap(e, s4Action(e, "刷新运行日志").boundsInRoot.center)
            repeat(40) { e.advanceFrameBy(16) }
            assertEquals(1, s4Nodes(e).count { n -> n.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == "日志读取失败" || it.text == "刷新未完成" } == true })
            assertEquals(1, s4Actions(e, "重试读取日志").size)
            assertFalse(s4HasText(e, "重试刷新"))
        }
    } }
}
