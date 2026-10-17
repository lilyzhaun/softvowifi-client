@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 独立复用S3RefreshErrorSpacingRenderTest的真实主题/3px竖条规则，不修改旧守护。
class ConfigurationPermissionGapRenderTest {
    @Test fun permissionPanelsKeepTheExistingCardGap() = onMotionTestThread { runTest {
        verifyGap(testScheduler, ConfigurationUiState.PermissionDenied("合成配置权限拒绝"),
            SimControlUiState.PermissionDenied("合成SIM权限拒绝"), "配置权限不足", "SIM 状态权限不足")
    } }

    @Test fun failurePanelsKeepTheExistingCardGap() = onMotionTestThread { runTest {
        verifyGap(testScheduler, ConfigurationUiState.Failure("合成配置失败"),
            SimControlUiState.Failure("合成SIM失败"), "配置读取失败", "SIM 状态读取失败")
    } }

    @Test fun simFailureKeepsTheExistingGapBeforeTheFirstConfigurationCard() = onMotionTestThread { runTest {
        val instance = ConfigurationInstance(ConfigurationInstanceId("sim-1"), "合成配置", ConfigurationLifecycle.STOPPED,
            ConfigurationTransport.AUTO, "", "", "", "", "epdg.invalid", "ims.invalid", "001", "01")
        verifyGap(testScheduler, ConfigurationUiState.Ready(listOf(instance)),
            SimControlUiState.Failure("合成SIM失败"), "SIM 状态读取失败", "合成配置")
    } }

    private fun verifyGap(scheduler: TestCoroutineScheduler, state: ConfigurationUiState,
        sim: SimControlUiState, firstTitle: String, nextTitle: String) {
        Fixture(scheduler, state, sim).use { f ->
            assertEquals(0, f.callbacks, "前提：初始合成状态不得触发刷新、提交或创建配置")
            val expected = settingsHubLayoutContract().cardGap.value
            assertEquals(12f, expected, "沿用既有12dp合同，不能改合同使测试通过")
            val gap = f.surfaceGap(firstTitle, nextTitle)
            println("CONFIGURATION_PERMISSION_GAP|$firstTitle→$nextTitle|actual_px=$gap|expected_dp=$expected")
            assertEquals(expected, gap.toFloat(), 1f, "真实card表面须相隔12dp（Density1），实测=${gap}px；非文字/卡内4dp间距")
        }
    }

    private class Fixture(private val scheduler: TestCoroutineScheduler, state: ConfigurationUiState,
        sim: SimControlUiState) : AutoCloseable {
        var callbacks = 0
        private val ready = state is ConfigurationUiState.Ready
        private var errorColor = 0
        private var canvas = 0
        private var card = 0
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private fun forbidden(): Nothing { callbacks++; error("间距测试不得执行刷新/配置业务") }
        private val environment = MotionTestEnvironment(scheduler, width = 420, height = 1200, durationScale = 0f) {
            SoftVoWiFiTheme(false) {
                val colors = LocalSoftVoWiFiColors.current
                val nextCard = MiuixTheme.colorScheme.surfaceContainer
                SideEffect { errorColor = colors.errorSurface.toArgb(); canvas = colors.canvas.toArgb(); card = nextCard.toArgb() }
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalLifecycleOwner provides lifecycle,
                    LocalNavigationEventDispatcherOwner provides back) {
                    SettingsConfigurationScreen(state, sim, SettingsConfigurationActions(
                        { _, _, _ -> forbidden() }, { forbidden() }, { forbidden() }))
                }
            }
        }
        init { repeat(3) { environment.advanceFrameBy(16) } }

        private fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            tree(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        private fun text(label: String): SemanticsNode {
            val found = nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
            assertEquals(1, found.size, "命中前提：真实页面须有唯一文字锚点：$label")
            return found.single()
        }
        fun surfaceGap(firstTitle: String, nextTitle: String): Int {
            // 文字仅定位卡内采样区；真正表面边界完全由像素决定。
            val first = text(firstTitle).boundsInRoot
            val next = text(nextTitle).boundsInRoot
            assertTrue(first.height > 0f && next.height > 0f && first.top >= 0f && first.bottom < next.top && next.bottom < 1200f,
                "命中前提：两个真实卡标题完整可见且顺序正确，不拿Text bounds当card bounds")
            val anchors = if (ready) listOf(text("现有配置仍可编辑。")) else nodes(merged = true).filter {
                it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)
                    ?.any { text -> text.text == "重试读取配置" } == true
            }
            assertEquals(1, anchors.size, "命中前提：真实首卡内部锚点必须唯一")
            val x = floor(next.left + 8f).toInt()
            val top = ceil(anchors.single().boundsInRoot.bottom).toInt() + 1
            val bottom = floor(next.top).toInt() - 2
            assertTrue(bottom - top in 6..160 && x >= 0 && x + 3 < 420 && top >= 0 && bottom < 1200,
                "采样前提：仅允许完整可见的3×160以内竖条")
            val rows = environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    (top until bottom).map { y -> IntArray(3) { dx -> bitmap.getColor(x + dx, y) } }
                }
            }
            val nextColor = if (ready) card else errorColor
            fun matches(row: IntArray, color: Int) = row.all { rgbDistance(it, color) <= 2 }
            assertTrue(rgbDistance(errorColor, canvas) >= 8 && rgbDistance(nextColor, canvas) >= 8,
                "采样前提：两卡表面均须与底色可区分，不换主题或降低阈值")
            assertTrue(rows.take(3).all { matches(it, errorColor) } && rows.takeLast(3).all { matches(it, nextColor) },
                "采样前提：条带两端各须至少3行已知纯表面，否则不是产品RED")
            if (errorColor == nextColor && rows.all { matches(it, errorColor) }) return 0 // 同色相接为有效零gap。
            val firstEnd = rows.indexOfFirst { !matches(it, errorColor) }
            val nextStart = rows.indexOfLast { !matches(it, nextColor) } + 1
            assertTrue(firstEnd >= 3 && rows.size - nextStart >= 3 && firstEnd <= nextStart,
                "采样前提：必须识别首卡末缘与后继卡起缘，不把圆角或裁剪当间距")
            assertTrue(rows.indices.all { y -> matches(rows[y], when {
                y < firstEnd -> errorColor
                y >= nextStart -> nextColor
                else -> canvas
            }) }, "采样前提：两表面之间必须全是连续底色，文字/边框/阴影不能充间距")
            return nextStart - firstEnd
        }
        private fun rgbDistance(a: Int, b: Int) = listOf(16, 8, 0).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
    }
}
