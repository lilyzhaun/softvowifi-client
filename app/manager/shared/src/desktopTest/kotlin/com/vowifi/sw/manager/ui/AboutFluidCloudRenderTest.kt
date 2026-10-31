@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.math.abs
import kotlin.math.roundToInt

class AboutFluidCloudRenderTest {
    @Test fun aboutDoesNotExposeTheUnmeasuredIdentityGapOnEntry() = onMotionTestThread { runTest {
        MotionTestEnvironment(testScheduler, 390, 844) {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner()) {
                SoftVoWiFiTheme(false) {
                    AppSubpageScene(AppRoute.SettingsAbout, spec(), onBack = {}) {
                        SettingsAboutScreen("0.3.48")
                    }
                }
            }
        }.use { e ->
            e.scene.render(0).close()
            assertFalse(nodes(e).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .any { text -> text.text == "Root 后端版本" } },
                "身份组尚未完成测量时不能先显示按错误占位高度布局的信息卡")
            testScheduler.runCurrent()
            e.scene.render(0).close()
            assertTrue(nodes(e).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .any { text -> text.text == "Root 后端版本" } },
                "身份组完成测量后信息卡必须出现")
        }
    } }

    @Test fun aboutKeepsTheFullColourLauncherIconInBothThemes() = onMotionTestThread { runTest {
        for (dark in listOf(false, true)) {
            MotionTestEnvironment(testScheduler, 390, 844, durationScale = 0f) {
                CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner()) {
                    SoftVoWiFiTheme(dark) { SettingsAboutScreen("0.3.48") }
                }
            }.use { e ->
                repeat(4) { e.advanceFrameBy() }
                e.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                    Bitmap.makeFromImage(image).use { bitmap ->
                        var green = 0
                        var cream = 0
                        for (y in 40 until 128) for (x in 151 until 239) {
                            when (bitmap.getColor(x, y)) {
                                0xFF0C655E.toInt() -> green++
                                0xFFFFF8EF.toInt() -> cream++
                            }
                        }
                        assertTrue(green > 100 && cream > 100,
                            "关于页应直接展示完整原色应用图标，不是主题染色的前景轮廓：dark=$dark green=$green cream=$cream")
                    }
                }
            }
        }
    } }

    @Test fun aboutHasItsOwnIdentityAndKeepsTheExistingInformationAndBackOwner() = onMotionTestThread { runTest {
        var backs = 0
        val owner = resumedOwner()
        MotionTestEnvironment(testScheduler, 390, 844) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SoftVoWiFiTheme(false) {
                    AppSubpageScene(AppRoute.SettingsAbout, spec(), onBack = { backs++ }) {
                        SettingsAboutScreen("0.3.48")
                    }
                }
            }
        }.use { e ->
            repeat(12) { e.advanceFrameBy() }
            assertTrue(texts(e).contains("SoftVoWiFi"), "关于页需要独立的应用标识组，而非只有两条信息行")
            assertTrue(texts(e).containsAll(listOf("Root 后端版本", "版本 0.3.48", "版本信息", "更多信息", "SoftVoWiFi 管理应用")))
            val headerVersion = nodes(e).filter { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "版本 0.3.48" } }
                .minBy { it.boundsInRoot.top }.boundsInRoot
            val information = nodes(e).single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "版本信息" } }.boundsInRoot
            assertTrue(information.top - headerVersion.bottom in 112f..160f,
                "标识组和信息卡间距不得重复计入顶栏/顶部内距：${information.top - headerVersion.bottom}")
            assertEquals(1, nodes(e).count { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) })
            val back = nodes(e).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("返回") == true }
            assertTrue(back.boundsInRoot.width >= 48f && back.boundsInRoot.height >= 48f)
            e.press(back.boundsInRoot.center); e.release(back.boundsInRoot.center)
            repeat(24) { e.advanceFrameBy() }
            assertEquals(1, backs)
        }
    } }

    @Test fun bothThemesAndLargeTypeKeepTheLastInformationReachableWithZeroMotion() = onMotionTestThread { runTest {
        for (dark in listOf(false, true)) {
            MotionTestEnvironment(testScheduler, 280, 640, durationScale = 0f) {
                CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner(), LocalDensity provides Density(1f, 2f)) {
                    SoftVoWiFiTheme(dark) {
                        AppSubpageScene(AppRoute.SettingsAbout, spec(), onBack = {}) { SettingsAboutScreen("0.3.48") }
                    }
                }
            }.use { e ->
                repeat(10) { e.advanceFrameBy() }
                assertTrue(texts(e).contains("SoftVoWiFi"))
                repeat(12) { e.scroll(Offset(140f, 450f), Offset(0f, 150f)); e.advanceFrameBy(32) }
                val last = nodes(e).single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "SoftVoWiFi 管理应用" } }
                assertTrue(last.boundsInRoot.top >= 0f && last.boundsInRoot.bottom <= 640f, "200%字号末项必须能滚入视口，dark=$dark bounds=${last.boundsInRoot}")
                assertTrue(nodes(e).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    .config[SemanticsActions.ScrollBy].action != null)
            }
        }
    } }

    @Test fun theRealSingleScrollOwnerCollapsesAndReversesTheSameHeader() = onMotionTestThread { runTest {
        lateinit var header: AboutHeaderState
        MotionTestEnvironment(testScheduler, 390, 844) {
            CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner()) {
                SoftVoWiFiTheme(false) {
                    AppSubpageScene(AppRoute.SettingsAbout, spec(), onBack = {}) {
                        val state = LocalAboutHeaderState.current!!
                        SideEffect { header = state }
                        SettingsAboutScreen("/data/private/unsafe")
                    }
                }
            }
        }.use { e ->
            repeat(6) { e.advanceFrameBy() }
            assertEquals(0f, header.progress)
            assertTrue(texts(e).contains("版本 未知"))
            assertFalse(texts(e).any { it.contains("/data/") })
            e.press(Offset(195f, 600f)); e.advanceFrameBy()
            e.move(Offset(195f, 580f)); e.advanceFrameBy()
            e.move(Offset(195f, 500f)); e.advanceFrameBy()
            val forward = header.progress
            assertTrue(forward > 0f && forward < 1f, "必须实际验证滚动中帧而非只看终值")
            e.move(Offset(195f, 540f)); e.advanceFrameBy()
            assertTrue(header.progress < forward, "反向输入须立即还原同一个进度，不启动第二段动画")
            e.release(Offset(195f, 540f)); e.advanceFrameBy()
            header.list.requestScrollToItem(1); repeat(3) { e.advanceFrameBy() }
            assertEquals(1f, header.progress)
            assertTrue(texts(e).containsAll(listOf("关于", "Root 后端版本", "更多信息")))
            assertEquals(1, nodes(e).count { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) })
        }
    } }

    @Test fun realShaderChangesPixelsButPausesWhenInactiveBackgroundedHiddenOrMotionDisabled() = onMotionTestThread { runTest {
        val owner = resumedOwner()
        val lifecycle = owner.lifecycle as LifecycleRegistry
        var active by mutableStateOf(true)
        var progress by mutableFloatStateOf(0f)
        var scale by mutableFloatStateOf(1f)
        val motion = object : MotionDurationScale { override val scaleFactor get() = scale }
        MotionTestEnvironment(testScheduler, 320, 640, motionScale = motion) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalAppRouteActive provides active) {
                SoftVoWiFiTheme(false) { AboutCloudBackground({ progress }, shaderSupported = true) }
            }
        }.use { e ->
            fun sample() = pixels(e, testScheduler.currentTime)
            fun advance() { repeat(12) { e.advanceFrameBy(32) } }
            fun assertFrozen(reason: String) {
                repeat(2) { e.advanceFrameBy() }
                val before = sample(); advance()
                assertTrue(before.contentEquals(sample()), reason)
                assertFalse(e.scene.hasInvalidations(), "$reason：必须同时停止请求后续帧，不能只隐藏动画")
            }
            advance()
            val before = sample(); advance()
            assertFalse(before.contentEquals(sample()), "必须真实编译并绘制动态shader，不用存在动画API代替")
            assertTrue(e.scene.hasInvalidations(), "前提：活动云层确实请求下一帧")
            active = false; assertFrozen("路由失活应冻结云层")
            active = true; advance()
            assertFalse(before.contentEquals(sample()))
            lifecycle.currentState = Lifecycle.State.STARTED; assertFrozen("后台应冻结云层")
            lifecycle.currentState = Lifecycle.State.RESUMED; advance()
            scale = 0f; assertFrozen("系统动画缩放0应保留静态云而非继续流动")
            scale = 1f; advance()
            val resumed = sample(); advance()
            assertFalse(resumed.contentEquals(sample()), "重新开启动画应恢复当前云层")
            progress = 1f; assertFrozen("云完全滚出后不继续流动")
        }
    } }

    @Test fun aboutToolbarMaterialBlendsContinuouslyAndReversesWithoutFadingTheBackControl() = onMotionTestThread { runTest {
        for (blur in listOf(false, true)) for (scale in listOf(1f, 0f)) {
            lateinit var header: AboutHeaderState
            val chrome = appChromeSpec(AppearanceSettings.Default.copy(blurRequested = blur), blur)
            MotionTestEnvironment(testScheduler, 390, 844, durationScale = scale) {
                CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner()) {
                    SoftVoWiFiTheme(false) {
                        AppSubpageScene(AppRoute.SettingsAbout, chrome, onBack = {}) {
                            val state = LocalAboutHeaderState.current!!
                            SideEffect { header = state; state.spacerHeightPx = 1000 }
                            LazyColumn(state = state.list, modifier = Modifier.fillMaxSize()) {
                                item { Box(Modifier.fillMaxWidth().height(3000.dp).background(Color(0xFF1078C0))) }
                            }
                        }
                    }
                }
            }.use { e ->
                fun color() = e.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                    Bitmap.makeFromImage(image).use { it.getColor(350, 28) }
                }
                fun channel(value: Int, shift: Int) = value shr shift and 255
                repeat(6) { e.advanceFrameBy() }
                val start = color()
                e.press(Offset(195f, 600f)); e.advanceFrameBy()
                e.move(Offset(195f, 580f)); e.advanceFrameBy()
                e.move(Offset(195f, 500f)); e.advanceFrameBy()
                val partial = header.progress
                assertTrue(partial > 0f && partial < 1f, "须采到真实滚动中间进度")
                val middle = color()
                e.move(Offset(195f, 540f)); e.advanceFrameBy()
                assertTrue(header.progress < partial)
                val reverse = color()
                e.release(Offset(195f, 540f)); e.advanceFrameBy()
                header.list.requestScrollToItem(0, 1000); repeat(3) { e.advanceFrameBy() }
                assertEquals(1f, header.progress)
                val end = color()
                assertTrue(abs(channel(start, 16) - channel(end, 16)) > 100, "材质采样必须实际遮挡有色正文")
                for (shift in listOf(0, 8, 16)) {
                    val expected = (channel(start, shift) * (1f - partial) + channel(end, shift) * partial).roundToInt()
                    assertTrue(abs(channel(middle, shift) - expected) <= 3,
                        "About材质不能在终点跳变：blur=$blur scale=$scale progress=$partial actual=${channel(middle, shift)} expected=$expected")
                }
                assertTrue(abs(channel(reverse, 16) - channel(start, 16)) < abs(channel(middle, 16) - channel(start, 16)))
                val back = nodes(e).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("返回") == true }
                assertTrue(back.boundsInRoot.width >= 48f && back.config.contains(SemanticsActions.OnClick))
            }
        }
    } }

    @Test fun unsupportedShaderKeepsAVisibleStaticCloudInBothThemes() = onMotionTestThread { runTest {
        for (dark in listOf(false, true)) {
            MotionTestEnvironment(testScheduler, 320, 640) {
                CompositionLocalProvider(LocalLifecycleOwner provides resumedOwner()) {
                    SoftVoWiFiTheme(dark) { AboutCloudBackground({ 0f }, shaderSupported = false) }
                }
            }.use { e ->
                repeat(3) { e.advanceFrameBy() }
                val before = pixels(e, testScheduler.currentTime)
                assertTrue(before.toSet().size > 20, "回退须有真实静态配色，不能是空白表面")
                repeat(12) { e.advanceFrameBy(32) }
                assertTrue(before.contentEquals(pixels(e, testScheduler.currentTime)))
            }
        }
    } }

    private fun pixels(e: MotionTestEnvironment, millis: Long): IntArray = e.scene.render(millis * 1_000_000L).use { image ->
        Bitmap.makeFromImage(image).use { bitmap ->
            IntArray(384) { index -> bitmap.getColor(index % 24 * 12 + 6, index / 24 * 16 + 8) }
        }
    }

    private fun spec() = appChromeSpec(AppearanceSettings.Default.copy(blurRequested = false), false)
    private fun resumedOwner() = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return e.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun texts(e: MotionTestEnvironment) = nodes(e).flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
    }
}
