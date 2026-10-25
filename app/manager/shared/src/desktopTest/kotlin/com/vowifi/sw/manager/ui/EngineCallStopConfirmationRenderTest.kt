@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import kotlin.test.*

class EngineCallStopConfirmationRenderTest {
    @Test fun homeStopWarningUsesOfficialMiuixDialogAndButtonGeometryInsteadOfTheCommunicationAlert() = onMotionTestThread { runTest {
        fun geometry(e: MotionTestEnvironment) = listOf("停止引擎？", "SIM 1 正在通话，停止引擎会挂断当前通话。", "取消", "停止").map { label ->
            if (label == "取消" || label == "停止") stopAction(e, label).boundsInRoot
            else s3TextBounds(s4Nodes(e), label)
        }
        lateinit var expected: List<androidx.compose.ui.geometry.Rect>
        withHome(testScheduler, "IN_CALL", 1f, officialReference = true) { e, _, _ ->
            s4Frames(e, 60)
            expected = geometry(e)
        }
        withHome(testScheduler, "IN_CALL", 1f) { e, stops, _ ->
            s4Frames(e, 60)
            s4Tap(e, stopAction(e, "停止引擎").boundsInRoot.center); s4Frames(e, 60)
            assertTrue(s4HasText(e, "停止引擎？")); assertEquals(0, stops[0])
            val actual = geometry(e)
            assertEquals(expected, actual, "首页警示必须采用官方OverlayDialog默认标题/说明/按钮几何，不能继续复用自绘通信alert")
            assertTrue(stopAction(e, "取消").boundsInRoot.height >= 48f)
            assertTrue(stopAction(e, "停止").boundsInRoot.height >= 48f)
        }
    } }

    @Test fun activeCallRequiresConfirmationCancelDoesNothingAndRepeatedConfirmStopsOnce() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) withHome(testScheduler, "IN_CALL", scale) { e, stops, pulses ->
            s4Frames(e, 60)
            val stop = stopAction(e, "停止引擎")
            assertTrue(stop.boundsInRoot.center.y in 0f..1000f, "前提：真实首页停止入口在视口内")
            s4Tap(e, stop.boundsInRoot.center); s4Frames(e, 20)
            assertEquals(0, stops[0], "通话中先确认，不能点击首页后立即停止")
            assertTrue(s4HasText(e, "停止引擎？"))
            assertTrue(s4HasText(e, "SIM 1 正在通话，停止引擎会挂断当前通话。"))
            pulses.clear()
            s4Tap(e, stopAction(e, "取消").boundsInRoot.center); s4Frames(e, 30)
            assertEquals(0, stops[0], "取消无停止副作用")
            assertEquals(listOf(HapticFeedbackType.Reject), pulses)
            assertTrue(s4Nodes(e, false).none { it.config.contains(SemanticsProperties.IsDialog) })

            s4Tap(e, stopAction(e, "停止引擎").boundsInRoot.center); s4Frames(e, 20)
            pulses.clear()
            val confirm = stopAction(e, "停止").boundsInRoot.center
            s4Tap(e, confirm)
            if (scale != 0f) assertEquals(0, stops[0], "正常动画必须等官方dismiss完成，不能按下即停止")
            s4Tap(e, confirm); s4Frames(e, 30)
            assertEquals(1, stops[0], "重复真实指针确认只停止一次，且等对话框退场后执行")
            assertEquals(listOf(HapticFeedbackType.Confirm), pulses)
        }
    } }

    @Test fun idleCallKeepsSingleTapStopWithoutDialog() = onMotionTestThread { runTest {
        withHome(testScheduler, "IDLE", 1f) { e, stops, _ ->
            s4Frames(e, 60)
            s4Tap(e, stopAction(e, "停止引擎").boundsInRoot.center); s4Frames(e, 20)
            assertEquals(1, stops[0])
            assertTrue(s4Nodes(e, false).none { it.config.contains(SemanticsProperties.IsDialog) })
        }
    } }

    @Test fun runningConfigurationSaveAndLeaveBothExplainTheCallAndCancelKeepsDraft() = onMotionTestThread { runTest {
        val warning = engineStopCallWarning(0)
        ConfigurationStateMotionRenderTest.Fixture(testScheduler, lifecycle = ConfigurationLifecycle.RUNNING,
            callWarning = warning).use { f ->
            f.editSmscDraft("+123"); f.advance(1000)
            f.tapText("保存并重启"); f.advance(1000)
            assertTrue(f.hasText("保存并重启实例？"))
            assertTrue(f.hasTextContaining("保存后将重启当前引擎实例，连接会暂时中断。"))
            assertTrue(f.hasTextContaining(warning))
            assertEquals(0, f.submissions)
            f.haptics.clear(); f.tapLast("取消"); f.advance(1000)
            assertEquals(0, f.submissions); assertTrue(f.draft.dirty)
            assertEquals(listOf(HapticFeedbackType.Reject), f.haptics)
            f.backController.request(); f.advance(1000)
            assertTrue(f.hasText("保存修改？")); assertTrue(f.hasTextContaining(warning))
            assertEquals(0, f.submissions)
            f.tapLast("保存并重启"); f.advance(1000)
            assertEquals(1, f.submissions); assertEquals(1, f.leaves)
        }
    } }

    private fun stopAction(e: MotionTestEnvironment, label: String) = s4Actions(e, label).single {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Button && !it.config.contains(SemanticsProperties.Disabled)
    }

    private fun withHome(scheduler: TestCoroutineScheduler, call: String, scale: Float, officialReference: Boolean = false,
        block: (MotionTestEnvironment, IntArray, MutableList<HapticFeedbackType>) -> Unit) {
        val fixture = (FixtureSimControlSource("matched-running").state.value as SimControlUiState.Ready).instances.single()
        val state = SimControlUiState.Ready(listOf(fixture.copy(slotIndex = 0,
            runtime = requireNotNull(fixture.runtime).copy(call = call))))
        val stops = intArrayOf(0)
        val pulses = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { pulses += hapticFeedbackType } }
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED } }
        val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
        val window = object : WindowInfo { override val isWindowFocused = true; override val containerSize = IntSize(480, 1000); override val containerDpSize = DpSize(480.dp, 1000.dp) }
        try { MotionTestEnvironment(scheduler, 480, 1000, scale, dispatcher = StandardTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalNavigationEventDispatcherOwner provides back,
                LocalWindowInfo provides window, LocalHapticFeedback provides haptics) { SoftVoWiFiTheme(false) {
                AppShell(AppNavigationState.initial(AppRoute.Home), {}, appearanceSettings = AppearanceSettings.Default.copy(blurRequested = false),
                    blurSupported = false) { route ->
                    if (route == AppRoute.Home) {
                        HomeScreen(FixtureObserverLogSource("empty").state.value, state,
                            FixtureEnvironmentDiagnosticsSource("ready").state.value, { RefreshResult.Completed },
                            { error("测试不得启动引擎") }, { stops[0]++ }, { error("测试不得创建配置") }, {})
                        if (officialReference) OverlayDialog(show = true, title = "停止引擎？",
                            summary = "SIM 1 正在通话，停止引擎会挂断当前通话。", onDismissRequest = {}) {
                            Row {
                                TextButton("取消", {}, Modifier.weight(1f), minHeight = 48.dp)
                                Spacer(Modifier.width(20.dp))
                                TextButton("停止", {}, Modifier.weight(1f), minHeight = 48.dp,
                                    colors = ButtonDefaults.textButtonColors(textColor = LocalSoftVoWiFiColors.current.error))
                            }
                        }
                    } else Box(Modifier.fillMaxSize())
                }
            } }
        }.use { block(it, stops, pulses) } } finally { back.navigationEventDispatcher.dispose() }
    }
}
