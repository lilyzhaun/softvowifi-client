@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.*
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Scaffold

class ConfigurationRebuildActionRenderTest {
    @Test fun cancelAndRapidRepeatedConfirmRespectTheSingleDecision() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) Fixture(testScheduler, scale).use { f ->
            f.open()
            assertEquals(0, f.submissions)
            f.tap("取消")
            s4Frames(f.environment, 40)
            assertEquals(0, f.submissions)
            assertEquals(listOf(HapticFeedbackType.Reject), f.pulses.takeLast(1))
            f.open()
            val confirm = f.action("重建").boundsInRoot.center
            s4Tap(f.environment, confirm)
            if (scale != 0f) assertEquals(0, f.submissions, "必须等官方退场完成，不在按下时清旧值")
            s4Tap(f.environment, confirm)
            s4Frames(f.environment, 40)
            assertEquals(1, f.submissions)
            assertTrue(s4Nodes(f.environment, false).none { it.config.contains(SemanticsProperties.IsDialog) })
        }
    } }

    @Test fun targetChangeOrLeavingDuringDismissCannotReplayTheConfirmedOldTarget() = onMotionTestThread { runTest {
        for (change in listOf("target", "route")) Fixture(testScheduler, 1f).use { f ->
            f.open()
            f.tap("重建")
            if (change == "target") f.row.value = f.row.value.copy(instance = f.instance.copy(
                draftOwnerId = com.vowifi.sw.manager.data.SimOwnerId.fromHmacSha256(ByteArray(32) { 2 }),
            ))
            else f.active.value = false
            s4Frames(f.environment, 50)
            assertEquals(0, f.submissions, "确认后的旧owner/离开页面都不能清配置：$change")
        }
    } }

    @Test fun runningOrPendingTargetHasNoDestructiveClick() = onMotionTestThread { runTest {
        Fixture(testScheduler, 1f).use { f ->
            f.row.value = f.row.value.copy(canRebuild = false)
            s4Frames(f.environment, 40)
            assertTrue(s4Actions(f.environment, "重建旧配置").none {
                !it.config.contains(SemanticsProperties.Disabled) && it.config.contains(SemanticsActions.OnClick)
            })
            assertEquals(0, f.submissions)
        }
    } }

    private class Fixture(scheduler: TestCoroutineScheduler, scale: Float) : AutoCloseable {
        val instance = ConfigurationInstance(ConfigurationInstanceId("sim-1"), "SIM 1", ConfigurationLifecycle.STOPPED,
            ConfigurationTransport.WIFI, "", "", "", "", "", "", "", "", formatState = ConfigurationFormatState.LEGACY)
        val row = mutableStateOf(ConfigurationRowPresentation("sim-1", "SIM 1", ConfigurationRowKind.REBUILD_REQUIRED,
            instance = instance, handle = SimHandle("sim-1"), canRebuild = true))
        val active = mutableStateOf(true)
        var submissions = 0
        val pulses = mutableListOf<HapticFeedbackType>()
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val lifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(480, 1000)
            override val containerDpSize = DpSize(480.dp, 1000.dp)
        }
        private val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { pulses += hapticFeedbackType }
        }
        val environment = MotionTestEnvironment(scheduler, 480, 1000, scale, dispatcher = StandardTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner, LocalNavigationEventDispatcherOwner provides back,
                LocalWindowInfo provides window, LocalHapticFeedback provides haptics, LocalAppRouteActive provides active.value) {
                SoftVoWiFiTheme(false) {
                    Scaffold(popupHost = { ManagerPopupHost() }) {
                        ConfigurationRebuildAction(row.value, pending = false) {
                            assertEquals(SimHandle("sim-1"), it)
                            submissions++
                            true
                        }
                    }
                }
            }
        }
        fun action(label: String) = s4Actions(environment, label).single {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                !it.config.contains(SemanticsProperties.Disabled)
        }
        fun tap(label: String) = s4Tap(environment, action(label).boundsInRoot.center)
        fun open() {
            s4Frames(environment, 40)
            tap("重建旧配置")
            s4Frames(environment, 30)
            assertTrue(s4HasText(environment, "重建旧配置？"))
        }
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
    }
}
