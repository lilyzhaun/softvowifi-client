@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S8HomeNoticeRecoveryRenderTest {
    @Test fun simReadFailureAndToolbarShareOneRealRefreshWaiter() = onMotionTestThread { runTest {
        RecoveryFixture(testScheduler).use { f ->
            val retry = f.button("重试").config[SemanticsActions.OnClick].action!!
            val toolbar = f.toolbar().config[SemanticsActions.OnClick].action!!
            f.tap("重试")
            assertEquals(1, f.refreshes)
            assertEquals(0, f.navigations)
            assertTrue(f.toolbar().config.contains(SemanticsProperties.Disabled))
            assertTrue(f.button("正在刷新").config.contains(SemanticsProperties.Disabled))
            retry(); toolbar()
            f.frames(2)
            assertEquals(1, f.refreshes, "旧卡内/顶栏回调也不能并发第二个真实刷新")
            f.gates.single().complete(Unit)
            f.frames(4)
            assertFalse(f.hasText("无法读取 SIM 与引擎状态"))
            assertFalse(f.toolbar().config.contains(SemanticsProperties.Disabled))
            val point = f.toolbar().boundsInRoot.center
            f.environment.press(point); f.environment.release(point); f.frames(2)
            assertEquals(2, f.refreshes, "卡内刷新结束后顶栏复用同一控制器继续可用")
        }
    } }

    @Test fun bothPermissionWarningsUseInlineRefreshAndNeverNavigateOrGrantRoot() = onMotionTestThread { runTest {
        for (diagnosticPermission in listOf(false, true)) {
            RecoveryFixture(testScheduler,
                engine = if (diagnosticPermission) SimControlUiState.Ready(emptyList()) else SimControlUiState.PermissionDenied("合成拒绝"),
                diagnostics = if (diagnosticPermission) EnvironmentDiagnosticsUiState.PermissionDenied("合成拒绝") else healthy()).use { f ->
                val title = if (diagnosticPermission) "环境检查权限不足" else "SIM 控制权限不足"
                assertTrue(f.hasText(title))
                assertTrue(f.hasText("在 KernelSU Manager 中为 SoftVoWiFi 授予 Root 权限后返回刷新"))
                assertFalse(f.nodes(true).any { node -> node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true },
                    "权限卡本体不得保留导航箭头点击")
                f.tap("刷新")
                assertEquals(1, f.refreshes)
                assertEquals(0, f.navigations)
            }
        }
    } }

    @Test fun retainedRetryIsRevokedWhileProvisioningOrAfterTheHomeRouteLeaves() = onMotionTestThread { runTest {
        RecoveryFixture(testScheduler).use { f ->
            val retry = f.button("重试").config[SemanticsActions.OnClick].action!!
            f.provisionBusy.value = true
            f.frames(2)
            assertTrue(f.button("重试").config.contains(SemanticsProperties.Disabled))
            retry()
            assertEquals(0, f.refreshes)
            f.provisionBusy.value = false
            f.active.value = false
            f.frames(2)
            retry()
            assertEquals(0, f.refreshes)
            assertEquals(0, f.navigations)
            f.active.value = true
            f.frames(2)
            f.tap("重试")
            assertEquals(1, f.refreshes)
        }
    } }

    private class RecoveryFixture(scheduler: TestCoroutineScheduler,
        engine: SimControlUiState = SimControlUiState.Failure("合成读取失败"),
        diagnostics: EnvironmentDiagnosticsUiState = healthy()) : AutoCloseable {
        val engine = mutableStateOf(engine)
        val active = mutableStateOf(true)
        val provisionBusy = mutableStateOf(false)
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var refreshes = 0
        var navigations = 0
        val environment = MotionTestEnvironment(scheduler, width = 390, height = 1200, durationScale = 0f) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalAppRouteActive provides active.value) {
                    HomeScreen(RegistrationUiState.Empty, this.engine.value, diagnostics,
                        onRefresh = {
                            refreshes++
                            CompletableDeferred<Unit>().also { gates += it }.await()
                            this.engine.value = SimControlUiState.Ready(emptyList())
                            RefreshResult.Completed
                        }, onStartEngine = { error("恢复用例不得启动引擎") },
                        onStopEngine = { error("恢复用例不得停止引擎") },
                        onProvision = { error("恢复用例不得创建配置") },
                        onOpenNotice = { navigations++ }, provisionBusy = provisionBusy.value)
                }
            }
        }
        init { frames(4) }
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy(16) } }
        fun nodes(merged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            visit(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)
        }
        fun hasText(value: String) = nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun button(value: String) = nodes(true).single {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun toolbar() = nodes(true).single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("刷新首页状态") == true &&
                it.config.contains(SemanticsActions.OnClick) }
        fun tap(value: String) {
            val point = button(value).boundsInRoot.center
            environment.press(point); environment.advanceFrameBy(8); environment.release(point); frames(2)
        }
        override fun close() = environment.close()
    }

    private companion object {
        fun healthy() = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList())
    }
}
