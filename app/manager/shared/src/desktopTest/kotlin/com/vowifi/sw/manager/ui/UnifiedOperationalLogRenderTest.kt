@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// U4：真实LogFilterRow/原生菜单；同组不再以引擎/启动分两个文件选择，事件类型仍保留。
class UnifiedOperationalLogRenderTest {
    @Test fun replacingTheRepresentativeFileInTheSameGroupKeepsPendingRefreshFailure() = onMotionTestThread { runTest {
        val group = "e".repeat(64)
        val engine = OperationalLogFile("a".repeat(64), "engine.log", "0001", group)
        val startup = OperationalLogFile("b".repeat(64), "launcher.log", "0001", group)
        var state by mutableStateOf(OperationalLogUiState.Ready(emptyList(), listOf(engine, startup), engine.id))
        val pending = CompletableDeferred<RefreshResult>()
        var requests = 0
        MotionTestEnvironment(testScheduler, 420, 1200, durationScale = 0f) {
            s4SmsHost { SettingsLogsScreen(state, SettingsLogsActions({}, {}, {}, {}, {}), false,
                onRefresh = { requests++; if (requests == 1) RefreshResult.Failed("合成组读取失败") else pending.await() }) }
        }.use { e ->
            fun frames(n: Int = 4) { repeat(n) { e.advanceFrameBy(16) } }
            fun failure() = s4Nodes(e, false).any { it.config.getOrNull(SemanticsProperties.Text)
                ?.any { text -> text.text == "刷新未完成" } == true }
            frames(); val refresh = s4Action(e, "刷新运行日志")
            e.press(refresh.boundsInRoot.center); e.advanceFrameBy(8); e.release(refresh.boundsInRoot.center); frames(40)
            assertEquals(1, requests); assertTrue(failure(), "前提：真实日志页刷新已发布失败")
            val retry = s4Action(e, "重试刷新")
            e.press(retry.boundsInRoot.center); e.advanceFrameBy(8); e.release(retry.boundsInRoot.center); frames()
            assertEquals(2, requests); assertTrue(failure())
            state = state.copy(files = listOf(startup), selectedFileId = startup.id)
            frames()
            assertTrue(failure(), "同group代表文件变化不是切线路，不能清空正在重试的旧失败")
            assertTrue(s4Actions(e, "重试刷新").single().config.contains(SemanticsProperties.Disabled))
            pending.complete(RefreshResult.Completed); frames(40)
            assertTrue(!failure(), "真实成功才移除同组失败")
        }
    } }

    @Test fun oneGroupHasOneRunningLogOptionAndKeepsEngineAsAnEventKind() = onMotionTestThread { runTest {
        val group = "e".repeat(64)
        val files = listOf(OperationalLogFile("a".repeat(64), "engine.log", "0001", groupKey = group),
            OperationalLogFile("b".repeat(64), "launcher.log", "0001", groupKey = group))
        assertTrue((OperationalLogKind.ENGINE to "引擎") in operationalLogKindOptions(), "不能把事件类型误当文件来源删掉")
        MotionTestEnvironment(testScheduler, 420, 900, durationScale = 0f) {
            s4SmsHost { LogFilterRow(LogFilterPresentation(files, files.first().id, OperationalLogFilter()),
                LogFilterActions({}, {}, {})) }
        }.use { e ->
            repeat(4) { e.advanceFrameBy(16) }
            val trigger = s4Actions(e, "日志来源").single { !it.config.contains(SemanticsProperties.Disabled) }
            assertTrue(trigger.boundsInRoot.width > 0 && trigger.boundsInRoot.height >= 48f, "前提：真实选择入口可见可点")
            e.press(trigger.boundsInRoot.center); e.advanceFrameBy(8); e.release(trigger.boundsInRoot.center)
            repeat(16) { e.advanceFrameBy(16) }
            val pattern = Regex("(?:运行日志|引擎|启动) •••• 0001")
            val menuLabels = s4Nodes(e, false).filter { it.config.contains(SemanticsActions.OnClick) }.mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.singleOrNull()?.takeIf(pattern::matches)
            }
            assertTrue(menuLabels.isNotEmpty(), "前提：原菜单须实际出现合法新/旧日志选项，不能拿未打开菜单当RED")
            assertEquals(listOf("运行日志 •••• 0001"), menuLabels, "同groupKey只允许一个真实运行日志选项")
        }
    } }
}
