@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S9AboutRuntimeRenderTest {
    @Test fun realAboutDestinationCallsTheInjectedReaderOnlyOncePerEntry() = onMotionTestThread { runTest {
        Fixture(testScheduler, wholeApp = true).use { f ->
            f.frames(2)
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
            assertTrue(f.hasText("外观设置"))
            val directory = Path.of("/tmp/opencode/s9-settings-icons-5367e95a")
            Files.createDirectories(directory)
            f.environment.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                requireNotNull(image.encodeToData()).use { Files.write(directory.resolve("root.png"), it.bytes) }
            }
            val appearance = f.nodes(true).single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "外观设置" } == true }
            f.environment.press(appearance.boundsInRoot.center); f.environment.release(appearance.boundsInRoot.center)
            f.advance(800)
            assertTrue(f.hasText("液态玻璃"))
            f.environment.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                requireNotNull(image.encodeToData()).use { Files.write(directory.resolve("appearance.png"), it.bytes) }
            }
            assertEquals(1, f.reads, "离开About后其它设置页面不能继续读取引擎")
        }
    } }

    @Test fun realReadUsesL1AndLeavingThePageCancelsItsOwnedWaiter() = onMotionTestThread { runTest {
        Fixture(testScheduler).use { f ->
            f.active.value = false
            f.frames(2)
            f.advance(200)
            assertEquals(0, f.reads, "首次失活时不得启动Root读取")
            assertFalse(f.hasText("正在读取引擎标识"), "没有持有真实读取时不能制造进行中")
            f.active.value = true
            repeat(2) { f.environment.advanceFrameBy(0) }
            f.frames(2)
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
            assertEquals(2, f.reads)
            f.active.value = false
            f.frames(2)
            assertEquals(1, f.cancelled)
            f.finish()
            f.frames(2)
            assertEquals(2, f.reads)
        }
    } }

    @Test fun finalRuntimeRowIsReachableAtTwoHundredPercentWithoutChangingTheIdentityOrPalette() = onMotionTestThread { runTest {
        Fixture(testScheduler, fontScale = 2f).use { f ->
            f.frames(2)
            assertEquals(1, f.reads)
            f.finish()
            f.advance(1000)
            requireNotNull(f.header).list.requestScrollToItem(1, 10_000)
            f.frames(5)
            val title = f.text("运行实例 PID 202").boundsInRoot
            val summary = f.text("与文件不一致").boundsInRoot
            assertTrue(title.height > 0f && summary.height > 0f)
            assertTrue(title.top >= 0f && summary.bottom <= 1200f,
                "大字末项标题和实际状态须完整可达：$title/$summary")
        }
    } }

    private class Fixture(private val scheduler: TestCoroutineScheduler, wholeApp: Boolean = false,
        fontScale: Float = 1f) : AutoCloseable {
        val active = mutableStateOf(true)
        var reads = 0
        var cancelled = 0
        var startedAt = 0L
        var header: AboutHeaderState? = null
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        private val read: suspend () -> EngineIntegritySnapshot = {
            reads++
            startedAt = scheduler.currentTime
            val gate = CompletableDeferred<Unit>().also { gates += it }
            try { gate.await() } catch (cancelledRead: CancellationException) { cancelled++; throw cancelledRead }
            EngineIntegritySnapshot("a".repeat(12), listOf(RunningEngineIntegrity(101, EngineExecutableMatch.SAME),
                RunningEngineIntegrity(202, EngineExecutableMatch.DIFFERENT)))
        }
        private val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
            FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), FixtureCallSource("idle"),
            readEngineIntegrity = read)
        val environment = MotionTestEnvironment(scheduler, 420, 1200, durationScale = 1f) { s4SmsHost {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalAppRouteActive provides active.value) {
                if (wholeApp) SoftVoWiFiApp(sources, {}, false, "1.2.3", initialSubpage = AppRoute.SettingsAbout,
                    appearanceSource = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(blurRequested = false)))
                else {
                    val aboutHeader = rememberAboutHeaderState()
                    SideEffect { header = aboutHeader }
                    CompositionLocalProvider(LocalAboutHeaderState provides aboutHeader) {
                        SettingsAboutScreen("1.2.3", appVersionCode = 12, readEngineIntegrity = read)
                    }
                }
            }
        } }
        fun finish() { gates.last().complete(Unit) }
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy(16) } }
        fun advance(ms: Long) { repeat((ms / 16).toInt()) { environment.advanceFrameBy(16) }; environment.advanceFrameBy(ms % 16) }
        fun advanceTo(time: Long) { val left = time - scheduler.currentTime; assertTrue(left >= 0); advance(left) }
        fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            visit(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        fun hasText(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun text(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        override fun close() = environment.close()
    }
}
