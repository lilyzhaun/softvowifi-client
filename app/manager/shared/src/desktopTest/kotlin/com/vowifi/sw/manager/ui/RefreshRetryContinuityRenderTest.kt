@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.*

/** 真实共享结果item/重试控件；仅第二次source读取由合成屏障挂起。 */
class RefreshRetryContinuityRenderTest {
    @Test fun retryKeepsTheErrorAndDisabledButtonUntilTheRealReadCompletes() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) {
            val read = CompletableDeferred<RefreshResult>()
            var requests = 0
            var canvas = 0
            lateinit var controller: ManagerRefreshController
            MotionTestEnvironment(testScheduler, 420, 900, durationScale = scale) {
                SoftVoWiFiTheme(false) {
                    val scope = rememberCoroutineScope()
                    val state = remember { ManagerRefreshController(scope, { true }) {
                        requests++; if (requests == 1) RefreshResult.Failed("合成读取失败") else read.await()
                    } }
                    val colors = LocalSoftVoWiFiColors.current
                    SideEffect { controller = state; canvas = colors.canvas.toArgb() }
                    LazyColumn(Modifier.fillMaxSize().background(colors.canvas)) {
                        item { Box(Modifier.fillMaxWidth().height(40.dp)) } // 正高度首项固定viewport，不让空状态item改变锚点。
                        managerRefreshResultItem(state, "refresh-status")
                        item { Box(Modifier.fillMaxWidth().height(40.dp).testTag("retry-successor")) }
                    }
                }
            }.use { e ->
                fun frames(n: Int = 1) { repeat(n) { e.advanceFrameBy(16) } }
                fun consume() { repeat(4) { e.advanceFrameBy(0) } }
                fun successor() = s4Nodes(e).single { it.config.getOrNull(SemanticsProperties.TestTag) == "retry-successor" }.boundsInRoot
                fun retry() = s4Nodes(e, false).singleOrNull { s4Matches(it, "重试刷新") && it.config.contains(SemanticsActions.OnClick) }
                fun hasError() = s4Nodes(e, false).any {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "刷新未完成" } == true
                }
                fun surfaceEnergy(): Int = e.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                    Bitmap.makeFromImage(image).use { bitmap -> (64 until 88).sumOf { y ->
                        (360 until 363).sumOf { x -> listOf(16, 8, 0).maxOf { shift ->
                            abs((bitmap.getColor(x, y) ushr shift and 255) - (canvas ushr shift and 255))
                        } }
                    } }
                }
                frames(4); val original = successor()
                assertTrue(controller.request()); consume(); frames(40)
                assertEquals(1, requests); assertTrue(hasError())
                val shown = successor(); val button = assertNotNull(retry()).boundsInRoot
                val fullEnergy = surfaceEnergy()
                assertTrue(fullEnergy > 8 * 72, "像素前提：3×24条带必须命中真实错误表面")
                assertTrue(shown.top > original.top + 48f && button.height >= 48f,
                    "布局前提：错误卡须真实让位且按钮至少48dp；原=$original，现=$shown，按钮=$button")
                e.press(button.center); e.advanceFrameBy(8); e.release(button.center); consume()
                assertEquals(2, requests); assertTrue(controller.running); assertFalse(controller.enabled)
                assertNull(controller.result, "业务result仍须真实清空，展示不得伪造本次结果")
                for (step in 0..4) {
                    if (step > 0) frames()
                    assertTrue(hasError(), "同scope重试帧${step}须保留实际上一失败")
                    val pendingButton = assertNotNull(retry(), "重试按钮保持位置，只撤输入")
                    assertEquals(button, pendingButton.boundsInRoot)
                    assertTrue(pendingButton.config.contains(SemanticsProperties.Disabled))
                    assertEquals(shown, successor(), "pending重试不得折叠后重开红框")
                }
                println("REFRESH_RETRY_PENDING|scale=$scale|frames=5|successor_top=${shown.top}|button_height=${button.height}|requests=$requests")
                assertEquals(2, requests)
                read.complete(RefreshResult.Completed); consume()
                assertFalse(controller.running); assertEquals(RefreshResult.Completed, controller.result)
                frames(4)
                assertFalse(hasError(), "旧退出绘制不得保留错误语义")
                assertNull(retry(), "旧退出按钮不得暴露可点击语义")
                if (scale > 0f) {
                    assertTrue(s4Nodes(e).any { it.config.getOrNull(SemanticsProperties.Text)
                        ?.any { text -> text.text == "刷新未完成" } == true },
                        "退场采样前提：仅未合并绘制树仍保留旧文字，不能将clear语义节点的子树当可访问内容")
                    assertTrue(successor().top > original.top + 1f && successor().top < shown.top - 1f,
                        "成功后须有真实尺寸spring中间帧，不立刻卸载整卡")
                    assertTrue(surfaceEnergy().toFloat() / fullEnergy in .02f.. .95f, "成功退场须有真实表面淡出中间像素")
                    println("REFRESH_RETRY_EXIT|successor_top=${successor().top}|surface_ratio=${surfaceEnergy().toFloat() / fullEnergy}")
                    e.press(button.center); e.release(button.center); consume()
                    assertEquals(2, requests, "旧位置在退出时不能再次提交")
                }
                frames(40)
                assertFalse(hasError()); assertEquals(original, successor(), "真实成功退场后不得留gap")
            }
        }
    } }

    @Test fun cancellationKeepsTheFailureButANewScopeDoesNotInheritIt() = onMotionTestThread { runTest {
        var owner by mutableStateOf(0)
        var read = CompletableDeferred<RefreshResult>()
        var calls = 0
        lateinit var controller: ManagerRefreshController
        MotionTestEnvironment(testScheduler, 420, 900) {
            SoftVoWiFiTheme(false) { key(owner) {
                val scope = rememberCoroutineScope()
                val state = remember { ManagerRefreshController(scope, { true }) {
                    calls++; if (calls == 1) RefreshResult.Failed("上一轮合成错误") else read.await()
                } }
                SideEffect { controller = state }
                LazyColumn { managerRefreshResultItem(state, "scope-failure") }
            } }
        }.use { e ->
            fun frames(n: Int = 1) { repeat(n) { e.advanceFrameBy(16) } }
            fun hasError() = s4Nodes(e).any { s4Matches(it, "刷新未完成") }
            frames(4); controller.request(); frames(40); assertTrue(hasError())
            controller.request(); frames(); controller.cancelWaiter(); frames(4)
            assertEquals(RefreshResult.Cancelled, controller.result)
            assertFalse(controller.running); assertTrue(hasError(), "取消不是成功，不清同scope上一失败")
            for (outcome in listOf(RefreshResult.Failed("新的合成错误\n第二行\n第三行"), RefreshResult.Busy, RefreshResult.Unsupported)) {
                read = CompletableDeferred(); assertTrue(controller.request()); frames()
                assertTrue(hasError(), "新终态前不得先空一帧")
                read.complete(outcome); frames(40)
                assertEquals(outcome, controller.result); assertFalse(controller.running); assertTrue(hasError())
            }
            owner++; frames(4)
            assertNull(controller.result); assertFalse(hasError(), "新scope不能沿用旧错误")
        }
    } }

    @Test fun changingFileScopeNeverResurrectsAnEarlierFailureOnReturn() = onMotionTestThread { runTest {
        var file by mutableStateOf("first")
        lateinit var controller: ManagerRefreshController
        MotionTestEnvironment(testScheduler, 420, 900) {
            SoftVoWiFiTheme(false) {
                val current = rememberManagerRefreshController({ RefreshResult.Failed("合成文件错误") }, presentationScope = file)
                SideEffect { controller = current }
                key(file) { LazyColumn { managerRefreshResultItem(current, "file-failure") } }
            }
        }.use { e ->
            fun frames() { repeat(40) { e.advanceFrameBy(16) } }
            fun hasError() = s4Nodes(e).any { s4Matches(it, "刷新未完成") }
            frames(); controller.request(); frames(); assertTrue(hasError())
            file = "second"; frames()
            assertFalse(hasError(), "另一个文件不能继承同controller旧展示")
            assertTrue(controller.result is RefreshResult.Failed, "换文件只清展示，不伪造业务result")
            file = "first"; frames(); assertFalse(hasError(), "返回文件也不是恢复旧一次读取的错误")
        }
    } }
}
