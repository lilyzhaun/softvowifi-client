@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogReadableRenderTest {
    @Test fun realPageDefaultsToKeyEventsAndCanShowAndHideTechnicalSteps() = onMotionTestThread { runTest {
        val step = OperationalLogEntry("2026-10-22 03:04:43.053", OperationalLogLevel.INFO,
            OperationalLogKind.DIAGNOSTIC, "SIM 1", OperationalLogMessage.EVENT, "DNS 通信参数已设置。",
            headline = "DNS 通信参数已设置", technical = true)
        val connected = step.copy(headline = "VoWiFi 隧道已建立", safeSummary = "安全连接已就绪。", technical = false)
        val failed = step.copy(headline = "DNS 查询失败", safeSummary = "查询超时。", level = OperationalLogLevel.ERROR)
        val file = OperationalLogFile("root-diagnostics-v1", "diagnostic.jsonl", "")
        val state = OperationalLogUiState.Ready(listOf(step, connected, failed), listOf(file), file.id)
        MotionTestEnvironment(testScheduler, 420, 1600, durationScale = 0f) {
            s4SmsHost { SettingsLogsScreen(state, SettingsLogsActions({}, {}, {}, {}, {}), false) }
        }.use { e ->
            fun frames() { repeat(8) { e.advanceFrameBy(16) } }
            fun text(value: String) = s4Nodes(e, false).any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true }
            fun click(label: String) {
                val node = s4Action(e, label)
                assertTrue(node.boundsInRoot.height >= 48f && node.boundsInRoot.top >= 0f && node.boundsInRoot.bottom <= 1600f,
                    "真实点击前提：$label ${node.boundsInRoot}")
                e.press(node.boundsInRoot.center); e.advanceFrameBy(8); e.release(node.boundsInRoot.center); frames()
            }
            frames()
            assertTrue(text("VoWiFi 隧道已建立"))
            assertTrue(text("DNS 查询失败"), "错误不能因technical字段而隐藏")
            assertFalse(text("DNS 通信参数已设置"))
            assertFalse(text("诊断事件"))
            click("查看全部步骤")
            assertTrue(text("返回关键记录"), "真实点击必须切换显示模式")
            assertTrue(text("DNS 通信参数已设置"))
            click("返回关键记录")
            assertFalse(text("DNS 通信参数已设置"))
            assertTrue(text("DNS 查询失败"))
        }
    } }
}
