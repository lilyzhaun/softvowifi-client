@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
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
import com.vowifi.sw.manager.data.RefreshResult
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 未运行：真实页面/刷新回调形成原StatePanel，仅测内存中的3px竖条，不采整图或替身卡。
class S3RefreshErrorSpacingRenderTest {
    @Test fun homeRefreshErrorUsesTheExistingCardGapAndSuccessRestoresTheOriginalPosition() = onMotionTestThread { runTest {
        verifySpacing(Fixture(testScheduler, settings = false), homeLayoutContract.cardGapDp)
    } }

    @Test fun diagnosticsRefreshErrorUsesItsCardGapAndLeavesNoGapAfterSuccessfulRetry() = onMotionTestThread { runTest {
        verifySpacing(Fixture(testScheduler, settings = true), diagnosticsUiContract().cardGapDp)
    } }

    private fun verifySpacing(fixture: Fixture, expectedGap: Int) = fixture.use { f ->
        val original = f.successor().boundsInRoot
        assertFalse(f.texts().any { it.contains("旧快照不代表本次读取成功") })
        f.refresh()
        assertEquals(1, f.requests, "前提：真实刷新按钮确已提交Failed读取")
        assertTrue(f.texts().any { it.contains("旧快照不代表本次读取成功") }, "旧数据必须明确stale，不能当成读取成功")
        assertTrue(f.successor().boundsInRoot.top > original.top, "前提：真实错误item已插在原数据之前")
        val gap = f.surfaceGap()
        f.next = RefreshResult.Completed
        f.retry()
        assertEquals(2, f.requests, "真实错误卡重试入口必须调用原刷新回调")
        assertFalse(f.texts().any { it.contains("旧快照不代表本次读取成功") })
        assertEquals(original, f.successor().boundsInRoot, "成功移除错误须恢复原数据位置，无条件padding不能留下额外空隙")
        assertEquals(expectedGap.toFloat(), gap.toFloat(), 1f,
            "刷新错误card的实际表面与后继card之间须有既有${expectedGap}dp间距（Density1），实测=${gap}px")
    }

    private class Fixture(scheduler: TestCoroutineScheduler, private val settings: Boolean) : AutoCloseable {
        var next: RefreshResult = RefreshResult.Failed("合成读取失败")
        var requests = 0
        private lateinit var palette: SoftVoWiFiColors
        private var nextSurface = 0
        private val diagnostics = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS,
            listOf(DiagnosticCheck(DiagnosticCheckId.MODULE, DiagnosticCheckStatus.PASS, "合成模块检查", "已确认")))
        private val successorTitle = if (settings) "环境检查通过" else "暂无活动 SIM"
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val environment = MotionTestEnvironment(scheduler, width = 420, height = 1200, durationScale = 0f) {
            SoftVoWiFiTheme(false) {
                val colors = LocalSoftVoWiFiColors.current
                val card = MiuixTheme.colorScheme.surfaceContainer
                SideEffect { palette = colors; nextSurface = (if (settings) card else colors.surfaceRaised).toArgb() }
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalLifecycleOwner provides lifecycle,
                    LocalNavigationEventDispatcherOwner provides back) {
                    if (settings) SettingsDiagnosticsScreen(diagnostics, onRefresh = { requests++; next })
                    else HomeScreen(RegistrationUiState.Empty, SimControlUiState.Ready(emptyList()), diagnostics,
                        onRefresh = { requests++; next }, onStartEngine = { error("间距测试不得启动") },
                        onStopEngine = { error("间距测试不得停止") }, onProvision = { error("间距测试不得创建配置") },
                        onOpenNotice = { error("间距测试不得导航") })
                }
            }
        }
        init { settle() }
        private fun settle() { repeat(180) { environment.advanceFrameBy(16) } } // 有界退出库指示/回弹，不改变业务结果。
        private fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            tree(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        fun successor(): SemanticsNode {
            if (settings) return nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == successorTitle } == true }
            // Home's chip, hero heading and facts can share copy. Match the actual heading,
            // whose natural text height is uniquely larger, rather than an assumed label count.
            val matches = nodes().filter {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == successorTitle } == true
            }.sortedBy { it.boundsInRoot.top }
            assertTrue(matches.size >= 2, "命中前提：首页主卡与facts必须均已实际渲染")
            val headingHeight = matches.maxOf { it.boundsInRoot.height }
            val hero = matches.single { it.boundsInRoot.height == headingHeight }
            val fact = matches.last()
            assertTrue(hero.boundsInRoot.bottom < fact.boundsInRoot.top && hero.boundsInRoot.height > fact.boundsInRoot.height,
                "命中前提：上方须是较大行高的主卡标题，下方才是facts值；不能以列表任意first替代主卡")
            return hero
        }
        private fun retryButton() = nodes(merged = true).single {
            it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "重试刷新" } == true
        }
        private fun tap(node: SemanticsNode) {
            assertTrue(node.boundsInRoot.height >= 48f && node.boundsInRoot.bottom <= 1200f, "命中前提：真实按钮须完整可见")
            val point = node.boundsInRoot.center
            environment.press(point); environment.advanceFrameBy(8); environment.release(point); settle()
        }
        fun refresh() = tap(nodes(merged = true).single {
            it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.contains(if (settings) "刷新诊断" else "刷新首页状态") == true
        })
        fun retry() = tap(retryButton())
        fun surfaceGap(): Int {
            val retry = retryButton().boundsInRoot
            val next = successor().boundsInRoot
            // 后继标题的左内区在其直边内；从按钮下缘开始，排除旧卡文字/按钮与后继标题。
            val x = floor(next.left + 8f).toInt()
            val top = ceil(retry.bottom).toInt() + 1
            val bottom = floor(next.top).toInt() - 2
            assertTrue(bottom - top in 6..160 && x >= 0 && x + 3 < 420 && top >= 0 && bottom < 1200,
                "采样前提：仅允许完整可见的3×160以内竖条；retry=$retry，next=$next")
            val rows = environment.scene.render(schedulerTime()).use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    (top until bottom).map { y -> IntArray(3) { dx -> bitmap.getColor(x + dx, y) } }
                }
            }
            val error = palette.errorSurface.toArgb()
            val canvas = palette.canvas.toArgb()
            fun matches(row: IntArray, color: Int) = row.all { spacingRgbDistance(it, color) <= 2 }
            assertTrue(spacingRgbDistance(error, canvas) >= 8 && spacingRgbDistance(nextSurface, canvas) >= 8,
                "采样前提：真实主题三种表面须可区分，不能换主题或降低阈值")
            val errorRows = rows.indices.filter { matches(rows[it], error) }
            val nextRows = rows.indices.filter { matches(rows[it], nextSurface) }
            assertTrue(errorRows.size >= 3 && nextRows.size >= 3,
                "采样前提：竖条必须命中至少3行纯错误/后继表面，不能把裁剪或圆角当间距")
            val errorLast = errorRows.last()
            val nextFirst = nextRows.first()
            assertTrue(errorLast < nextFirst, "采样前提：真实表面顺序须错误→底色→后继，不采用其他对象边界")
            assertTrue((errorLast + 1 until nextFirst).all { matches(rows[it], canvas) },
                "采样前提：间隙须全部为实际页面底色，文字/边框/阴影不能计入")
            return nextFirst - errorLast - 1
        }
        private val clock = scheduler
        private fun schedulerTime() = clock.currentTime * 1_000_000L
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
    }
}

private fun spacingRgbDistance(a: Int, b: Int) = listOf(16, 8, 0).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }
