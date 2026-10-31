@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import com.vowifi.sw.manager.ui.testing.captureTestDiagnostics
import com.vowifi.sw.manager.ui.testing.diagnosticCaptureDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs

class S9AboutRuntimeRenderTest {
    @Test fun enteringAndResumingEngineInformationNeverStartsAnAutomaticIntegrityRead() = onMotionTestThread { runTest {
        Fixture(testScheduler, initialLifecycle = Lifecycle.State.STARTED, scene = true, height = 1600).use { f ->
            f.frames(3)
            assertEquals(0, f.reads)
            val before = f.informationGeometry()
            f.lifecycle.currentState = Lifecycle.State.RESUMED
            f.advance(400)
            assertEquals(0, f.reads, "进入关于页不能自行发起引擎检查")
            assertFalse(f.hasText("正在读取引擎标识"))
            assertEquals(before, f.informationGeometry(), "进入前台不能替换信息文字或改变行位置")
            f.lifecycle.currentState = Lifecycle.State.STARTED
            f.frames(2)
            f.lifecycle.currentState = Lifecycle.State.RESUMED
            f.advance(400)
            assertEquals(0, f.reads, "恢复前台不能自动检查或自动续读")
            assertFalse(f.hasText("正在读取引擎标识"))
            assertEquals(before, f.informationGeometry(), "恢复前台也不能触发检查文字变更")
        }
    } }

    @Test fun realAppAboutDestinationDoesNotReadBeforeAManualAction() = onMotionTestThread { runTest {
        Fixture(testScheduler, wholeApp = true).use { f ->
            f.advance(800)
            assertEquals(0, f.reads, "真实App接线也必须等待手动点击，不只改独立页面夹具")
            assertFalse(f.hasText("正在读取引擎标识"))
            assertTrue(f.hasText("引擎信息"))
            assertFalse(f.hasText("检查引擎") || f.hasText("引擎标识"), "关于首页只提供子页入口")
            f.openEngineInformation()
            assertEquals(0, f.reads, "打开引擎信息子页也不自动检查")
        }
    } }

    @Test fun quickReadCrossfadesTheActualSummaryInkWithoutBlankFrames() = onMotionTestThread { runTest {
        checkSummaryInk(testScheduler, scale = 1f, slow = false)
    } }

    @Test fun slowReadCrossfadesWaitingLoadingAndResultWithoutBlankFrames() = onMotionTestThread { runTest {
        checkSummaryInk(testScheduler, scale = 1f, slow = true)
    } }

    @Test fun zeroMotionQuickSummaryReplacementHasNoIntermediateInk() = onMotionTestThread { runTest {
        checkSummaryInk(testScheduler, scale = 0f, slow = false)
    } }

    @Test fun zeroMotionSlowSummaryReplacementHasNoIntermediateInk() = onMotionTestThread { runTest {
        checkSummaryInk(testScheduler, scale = 0f, slow = true)
    } }

    private fun checkSummaryInk(scheduler: TestCoroutineScheduler, scale: Float, slow: Boolean) {
        Fixture(scheduler, initialLifecycle = Lifecycle.State.STARTED, scene = true, height = 1600,
            durationScale = scale, result = EngineIntegritySnapshot("a".repeat(12), emptyList())).use { f ->
            f.frames(3)
            val titles = listOf("引擎标识", "运行实例").map { f.text(it).boundsInRoot }
            val regions = titles.map { Rect(it.left, it.bottom, 390f, it.bottom + 20f) }
            val card = f.cardVerticalSpan()
            fun sample() = f.textInk(regions)
            fun delta(a: IntArray, b: IntArray) = a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size
            fun checkTransition(frames: List<List<IntArray>>, changedRows: List<Int>) {
                for (row in changedRows) {
                    val start = frames.first()[row]
                    val end = frames.last()[row]
                    val distance = delta(start, end)
                    assertTrue(distance > 1.0, "前提：采样区内的真实字形发生变化，不能只测空白或标题")
                    val middle = frames.count { delta(it[row], start) > distance * .05 && delta(it[row], end) > distance * .05 }
                    if (scale == 0f) assertEquals(0, middle, "零动画直接换成同一终值，不残留半透明旧字")
                    else {
                        assertTrue(middle >= 3, "真实摘要至少有连续中间字形帧，不能一帧硬切：slow=$slow row=$row middle=$middle")
                        val largestStep = frames.zipWithNext().maxOf { (a, b) -> delta(a[row], b[row]) }
                        assertTrue(largestStep < distance * .65, "文字不能在单帧完成绝大部分替换：$largestStep/$distance")
                    }
                    val minimumInk = minOf(start.sum(), end.sum())
                    assertTrue(frames.all { it[row].sum() >= minimumInk * .60 }, "交界不能先清空旧字再显示新字")
                }
            }
            f.lifecycle.currentState = Lifecycle.State.RESUMED
            f.frames(2)
            assertEquals(0, f.reads)
            f.startManualCheck()
            if (slow) {
                val loadingFrames = mutableListOf(sample())
                repeat(28) { f.frames(1); loadingFrames += sample() }
                assertTrue(f.hasText("正在读取引擎标识"))
                checkTransition(loadingFrames, listOf(0))
            }
            val resultFrames = mutableListOf(sample())
            f.finish()
            repeat(45) {
                f.frames(1)
                resultFrames += sample()
                assertEquals(card, f.cardVerticalSpan())
                assertEquals(titles, listOf("引擎标识", "运行实例").map { f.text(it).boundsInRoot })
                if (f.hasText("a".repeat(12))) {
                    assertFalse(f.hasText("尚未检查"), "退出旧字只参与绘制，不能暴露过时语义")
                    assertFalse(f.hasText("正在读取引擎标识"), "旧加载摘要不能和结果重复播报")
                }
            }
            assertTrue(f.hasText("a".repeat(12)))
            assertTrue(f.hasText("当前没有运行实例"))
            checkTransition(resultFrames, listOf(0, 1))
            assertEquals(1, f.reads)
        }
    }

    @Test fun enteringBeforeResumeDoesNotInventAnUnreadableResult() = onMotionTestThread { runTest {
        Fixture(testScheduler, initialLifecycle = Lifecycle.State.STARTED, scene = true).use { f ->
            f.frames(3)
            assertEquals(0, f.reads, "前提：进入转场期间页面尚未RESUMED，真实读取没有开始")
            assertFalse(f.hasText("无法读取"), "未开始读取不能先渲染失败结果，再在RESUMED时撤下")
            assertFalse(f.hasText("正在读取引擎标识"), "没有真实读取不能伪造加载进度")
        }
    } }

    @Test fun quickReadKeepsTheActualCardSurfaceAcrossResume() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) checkEntryCardSurface(testScheduler, scale, slow = false)
    } }

    @Test fun slowReadKeepsTheActualCardSurfaceAcrossLoadingAndExit() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) checkEntryCardSurface(testScheduler, scale, slow = true)
    } }

    @Test fun unreadableTerminalRequiresACompletedReadAndKeepsTheCardSurface() = onMotionTestThread { runTest {
        Fixture(testScheduler, initialLifecycle = Lifecycle.State.STARTED, scene = true, height = 1600,
            result = EngineIntegritySnapshot()).use { f ->
            f.frames(3)
            val card = f.cardVerticalSpan()
            assertEquals(0, f.reads)
            assertFalse(f.hasText("无法读取"))
            f.lifecycle.currentState = Lifecycle.State.RESUMED
            f.frames(2)
            assertEquals(0, f.reads)
            f.startManualCheck()
            assertEquals(1, f.reads)
            assertFalse(f.hasText("无法读取"))
            f.finish()
            repeat(12) { f.frames(1); assertEquals(card, f.cardVerticalSpan()) }
            assertTrue(f.hasText("无法读取"), "真实不可读终态必须呈现，不能一直用等待占位隐藏错误")
            assertFalse(f.hasText("尚未检查"))
            assertFalse(f.hasText("正在读取引擎标识"))
        }
    } }

    private fun checkEntryCardSurface(scheduler: TestCoroutineScheduler, scale: Float, slow: Boolean) {
        Fixture(scheduler, initialLifecycle = Lifecycle.State.STARTED, scene = true, height = 1600,
            durationScale = scale, result = EngineIntegritySnapshot("a".repeat(12), emptyList())).use { f ->
            f.frames(3)
            assertEquals(0, f.reads)
            val card = f.cardVerticalSpan()
            val beforeRows = f.informationGeometry()
            assertTrue(card.last - card.first > 150, "前提：实际采样到了子页检查和结果卡表面，不是文字或空白背景")
            fun checkCard() {
                val actual = f.cardVerticalSpan()
                assertEquals(card, actual,
                    "进入前、真实读取启动、L1等待/显示/退出与结果之间，卡面不能突然缩短再撑开：scale=$scale slow=$slow " +
                        "clock=${scheduler.currentTime} started=${f.startedAt} before=$beforeRows after=${f.informationGeometry()}")
                assertTrue(f.hasText("引擎标识"), "固定信息行不能被加载态整段卸载")
            }
            f.lifecycle.currentState = Lifecycle.State.RESUMED
            f.frames(1)
            assertEquals(0, f.reads)
            f.startManualCheck()
            assertEquals(1, f.reads)
            checkCard()
            repeat(if (slow) 13 else 1) { f.frames(1); checkCard() }
            if (slow) assertTrue(f.hasText("正在读取引擎标识"))
            else assertFalse(f.hasText("正在读取引擎标识"))
            f.finish()
            repeat(45) { f.frames(1); checkCard() }
            assertTrue(f.hasText("a".repeat(12)))
            assertTrue(f.hasText("当前没有运行实例"))
            assertFalse(f.hasText("正在读取引擎标识"))
            assertEquals(1, f.reads)
        }
    }

    @Test fun realAboutDestinationCallsTheInjectedReaderOnlyOnManualClick() = onMotionTestThread { runTest {
        Fixture(testScheduler, wholeApp = true).use { f ->
            f.frames(2)
            assertEquals(0, f.reads, "真实App进入About不自动读取")
            f.openEngineInformation()
            assertEquals(0, f.reads, "进入子页不自动读取")
            f.startManualCheck()
            assertEquals(1, f.reads, "真实App的About目的地必须接到Sources读取，不能只测独立页面")
            f.finish()
            f.advance(800)
            assertTrue(f.hasText("a".repeat(12)))
            f.advance(400)
            assertEquals(1, f.reads, "正常重组和云动画不能触发轮询Root")
            val back = f.nodes(true).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("返回") == true }
            f.environment.press(back.boundsInRoot.center); f.environment.release(back.boundsInRoot.center)
            f.advance(800)
            assertTrue(f.hasText("更多信息"), "引擎子页返回到关于，不直接跳设置根")
            assertFalse(f.hasText("检查引擎"))
            f.openEngineInformation()
            assertEquals(1, f.reads, "退出后重新进入引擎子页也不能自动读取")
            f.startManualCheck()
            assertEquals(2, f.reads)
            f.finish()
            f.advance(800)
            val backAgain = f.nodes(true).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("返回") == true }
            f.environment.press(backAgain.boundsInRoot.center); f.environment.release(backAgain.boundsInRoot.center)
            f.advance(800)
            assertTrue(f.hasText("更多信息"))
            val aboutBack = f.nodes(true).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("返回") == true }
            f.environment.press(aboutBack.boundsInRoot.center); f.environment.release(aboutBack.boundsInRoot.center)
            f.advance(800)
            assertTrue(f.hasText("外观设置"))
            val directory = diagnosticCaptureDirectory("s9-settings-icons")
            captureTestDiagnostics { Files.createDirectories(directory) }
            f.environment.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                captureTestDiagnostics {
                    requireNotNull(image.encodeToData()).use { Files.write(directory.resolve("root.png"), it.bytes) }
                }
            }
            val appearance = f.nodes(true).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "外观设置" } == true }
            f.environment.press(appearance.boundsInRoot.center); f.environment.release(appearance.boundsInRoot.center)
            f.advance(800)
            assertTrue(f.hasText("液态玻璃"))
            f.environment.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                captureTestDiagnostics {
                    requireNotNull(image.encodeToData()).use { Files.write(directory.resolve("appearance.png"), it.bytes) }
                }
            }
            assertEquals(2, f.reads, "离开About后其它设置页面不能继续读取引擎")
        }
    } }

    @Test fun realReadUsesL1AndLeavingThePageCancelsItsOwnedWaiter() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.active.value = false
            f.frames(2)
            f.advance(200)
            assertEquals(0, f.reads, "首次失活时不得启动Root读取")
            assertFalse(f.hasText("正在读取引擎标识"), "没有持有真实读取时不能制造进行中")
            f.startManualCheck()
            assertEquals(0, f.reads, "失活时即使点击旧按钮区域也不能发起读取")
            f.active.value = true
            repeat(2) { f.environment.advanceFrameBy(0) }
            f.frames(2)
            assertEquals(0, f.reads)
            f.startManualCheck()
            val point = f.checkButton().boundsInRoot.center
            repeat(3) { f.environment.press(point); f.environment.release(point) }
            f.frames(1)
            assertEquals(1, f.reads)
            f.advanceTo(f.startedAt + 149)
            assertFalse(f.hasText("正在读取引擎标识"))
            f.advanceTo(f.startedAt + 166)
            assertTrue(f.hasText("正在读取引擎标识"))
            f.finish()
            f.advance(100)
            assertTrue(f.hasText("正在读取引擎标识"), "已显示的真实进行中至少保持原L1最小时长")
            f.advance(700)
            assertFalse(f.hasText("正在读取引擎标识"))
            assertTrue(f.hasText("a".repeat(12)))
            f.active.value = false
            f.frames(2)
            f.active.value = true
            f.frames(2)
            assertEquals(1, f.reads, "恢复页面不自动重读")
            assertTrue(f.hasText("a".repeat(12)), "恢复不能先清空上次手动检查结果")
            f.startManualCheck()
            assertEquals(2, f.reads)
            assertTrue(f.hasText("a".repeat(12)), "主动重查在途也不能清空上次结果")
            assertTrue(f.hasText("仅手动读取；下方为上次检查结果。"), "旧结果必须明确标作上次检查，不能冒实时")
            f.active.value = false
            f.frames(2)
            assertEquals(1, f.cancelled)
            f.finish()
            f.frames(2)
            assertEquals(2, f.reads)
            f.active.value = true
            f.advance(400)
            assertEquals(2, f.reads, "取消后回来不得自动续读")
            assertTrue(f.hasText("a".repeat(12)))
            assertFalse(f.hasText("正在读取引擎标识"))
        }
    } }

    @Test fun completedIntegrityReadKeepsItsRowsVisibleDuringLoadingExit() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.frames(2)
            assertEquals(0, f.reads)
            f.startManualCheck()
            assertEquals(1, f.reads)
            f.advance(200)
            assertTrue(f.hasText("正在读取引擎标识"), "前提：真实读取超过L1后显示进行中")
            f.finish()
            // The L1 minimum keeps the indicator alive, but the completed result must
            // replace it immediately; it must not leave the card blank during exit.
            f.advance(300)
            assertTrue(f.hasText("引擎标识"),
                "真实结果已返回时，加载行退出期间仍须保留引擎信息行，不能让底部卡片闪空")
        }
    } }

    @Test fun finalRuntimeRowIsReachableAtTwoHundredPercentWithoutChangingTheIdentityOrPalette() = onMotionTestThread { runTest {
        Fixture(testScheduler, fontScale = 2f).use { f ->
            f.frames(2)
            assertEquals(0, f.reads)
            f.startManualCheck()
            assertEquals(1, f.reads)
            f.finish()
            f.advance(1000)
            f.scrollToEnd()
            val title = f.text("运行实例 PID 202").boundsInRoot
            val summary = f.text("与文件不一致").boundsInRoot
            assertTrue(title.height > 0f && summary.height > 0f)
            assertTrue(title.top >= 0f && summary.bottom <= 1200f,
                "大字末项标题和实际状态须完整可达：$title/$summary")
        }
    } }

    private class Fixture(private val scheduler: TestCoroutineScheduler, wholeApp: Boolean = false,
        fontScale: Float = 1f, initialLifecycle: Lifecycle.State = Lifecycle.State.RESUMED,
        scene: Boolean = false, private val height: Int = 1200, durationScale: Float = 1f,
        private val result: EngineIntegritySnapshot = EngineIntegritySnapshot("a".repeat(12), listOf(
            RunningEngineIntegrity(101, EngineExecutableMatch.SAME), RunningEngineIntegrity(202, EngineExecutableMatch.DIFFERENT))),
    ) : AutoCloseable {
        val active = mutableStateOf(true)
        private val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = initialLifecycle }
        }
        val lifecycle get() = owner.lifecycle
        var reads = 0
        var cancelled = 0
        var startedAt = 0L
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        private val read: suspend () -> EngineIntegritySnapshot = {
            reads++
            startedAt = scheduler.currentTime
            val gate = CompletableDeferred<Unit>().also { gates += it }
            try { gate.await() } catch (cancelledRead: CancellationException) { cancelled++; throw cancelledRead }
            result
        }
        private val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
            FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), FixtureCallSource("idle"),
            readEngineIntegrity = read)
        val environment = MotionTestEnvironment(scheduler, 420, height, durationScale = durationScale) { s4SmsHost {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalAppRouteActive provides active.value,
                LocalLifecycleOwner provides owner) {
                if (wholeApp) SoftVoWiFiApp(sources, {}, false, "1.2.3", initialSubpage = AppRoute.SettingsAbout,
                    appearanceSource = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(blurRequested = false)))
                else if (scene) AppSubpageScene(AppRoute.SettingsEngineInformation,
                    appChromeSpec(AppearanceSettings.Default.copy(blurRequested = false), false), onBack = {}) {
                    SettingsEngineInformationScreen(readEngineIntegrity = read)
                }
                else SettingsEngineInformationScreen(readEngineIntegrity = read)
            }
        } }
        fun finish() { gates.last().complete(Unit) }
        fun openEngineInformation() {
            val entry = nodes(true).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "引擎信息" } == true }
            environment.press(entry.boundsInRoot.center); environment.release(entry.boundsInRoot.center)
            advance(800)
        }
        fun scrollToEnd() {
            repeat(6) { environment.scroll(Offset(210f, height * .75f), Offset(0f, 400f)); frames(2) }
        }
        fun checkButton() = nodes(true).single { it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "检查引擎" } == true }
        fun startManualCheck() {
            val bounds = checkButton().boundsInRoot
            assertTrue(bounds.top >= 0f && bounds.bottom <= height, "手动检查按钮须真实位于视口：$bounds")
            environment.press(bounds.center); environment.release(bounds.center)
            frames(1)
        }
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy(16) } }
        fun advance(ms: Long) { repeat((ms / 16).toInt()) { environment.advanceFrameBy(16) }; environment.advanceFrameBy(ms % 16) }
        fun advanceTo(time: Long) { val left = time - scheduler.currentTime; assertTrue(left >= 0); advance(left) }
        fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            visit(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        fun hasText(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun text(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun cardVerticalSpan(): IntRange = environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { pixels ->
                fun isCardRow(y: Int): Boolean {
                    var white = 0
                    for (x in 30 until 390) {
                        if (pixels.getColor(x, y) == -1) white++
                        if (white > 270) return true
                        if (white + 389 - x <= 270) return false
                    }
                    return false
                }
                val top = (0 until height).firstOrNull(::isCardRow)
                assertTrue(top != null, "前提：真实卡片有可测量白色表面")
                top..(0 until height).last(::isCardRow)
            }
        }
        fun informationGeometry() = nodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.let { it to node.boundsInRoot }
        }
        fun textInk(regions: List<Rect>): List<IntArray> = environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { pixels ->
                regions.map { region ->
                    val width = region.width.toInt()
                    IntArray(width * region.height.toInt()) { index ->
                        val color = pixels.getColor(region.left.toInt() + index % width, region.top.toInt() + index / width)
                        255 - (((color shr 16) and 255) + ((color shr 8) and 255) + (color and 255)) / 3
                    }
                }
            }
        }
        override fun close() = environment.close()
    }
}
