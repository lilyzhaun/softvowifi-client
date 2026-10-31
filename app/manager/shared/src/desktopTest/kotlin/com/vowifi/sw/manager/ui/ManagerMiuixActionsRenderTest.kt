@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Messages
import kotlin.test.*

class ManagerMiuixActionsRenderTest {
    @Test fun navigationIconsStayNeutralWhileManagementRefreshRemainsBlue() = onMotionTestThread { runTest {
        for (dark in listOf(false, true)) MotionTestEnvironment(testScheduler, 420, 900) {
            SoftVoWiFiTheme(dark) { Column {
                ManagerIconAction(MiuixIcons.Back, "返回", ManagerAction.Navigate, {})
                ManagerIconAction(MiuixIcons.Refresh, "刷新", ManagerAction.Refresh, {})
            } }
        }.use { e ->
            s4Frames(e, 20)
            e.scene.render(testScheduler.currentTime * 1_000_000L).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                fun blue(label: String): Int {
                    val bounds = s4Nodes(e).single {
                        it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label)
                    }.boundsInRoot
                    var count = 0
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
                        val pixel = bitmap.getColor(x, y)
                        if ((pixel and 255) > (pixel ushr 16 and 255) + 40 && (pixel and 255) > (pixel ushr 8 and 255) + 25) count++
                    }
                    return count
                }
                assertTrue(blue("刷新") > 30, "蓝色管理刷新阳性对照")
                assertEquals(0, blue("返回"), "返回沿miuix原生正文色，不随管理主色染蓝：dark=$dark")
            } }
        }
    } }

    @Test fun pullCaptionsUseTheMiuixNeutralIndicatorColor() = onMotionTestThread { runTest {
        S3HomeRefreshEntryDraftTest.HomeEntryDriver(testScheduler).use { driver ->
            driver.frames(30); driver.holdReads = true
            fun assertNeutral(caption: String) {
                val node = s4Nodes(driver.environment, false).single {
                    it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == caption
                }
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                assertEquals(top.yukonga.miuix.kmp.basic.PullToRefreshDefaults.color,
                    layouts.single().layoutInput.style.color, "下拉提示不是管理动作，不染蓝")
            }
            driver.beginDownwardDrag(180f)
            assertNeutral("松开刷新")
            driver.finishDrag()
            driver.until("真实读取在途") { driver.refreshCalls == 1 }
            driver.untilPullOccupationStable()
            assertNeutral("正在刷新")
            driver.releaseRead(RefreshResult.Completed)
            driver.frames(100)
            assertEquals(1, driver.refreshCalls)
        }
    } }

    @Test fun detailActionsAreCenteredAndCompactAndUseTheOfficialMessagesGlyph() = onMotionTestThread { runTest {
        val owner = OWNER_A
        val history = CallHistoryState.Ready(listOf(historyWrite(peer = "+12025550123", outcome = CallOutcome.REJECTED,
            endedAtMs = T0 + 1000).toRecord(owner)), owner)
        MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
            Box(Modifier.fillMaxSize()) {
                CallHistoryDetailScreen(callHistoryGroups(history).single().handle, history, { _, _ -> }, null, null,
                    onCallback = {}, onMessage = { _, _ -> true })
                Box(Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter)) { PhoneStyle {
                    PhoneButton("参考短信", {}, icon = MiuixIcons.Messages, compact = true)
                } }
            }
        } }.use { e ->
            s4Frames(e, 20)
            fun face(label: String) = s4Nodes(e).single {
                it.config.getOrNull(SemanticsProperties.TestTag) == "phone-face-$label"
            }.boundsInRoot
            val call = face("回拨"); val sms = face("短信")
            assertEquals(210f, (call.center.x + sms.center.x) / 2, .5f)
            assertTrue(sms.center.x - call.center.x in 64f..132f, "两动作须向中间收紧，不能继续平分全屏")
            assertTrue(call.width >= 48 && sms.width >= 48 && call.height >= 48 && sms.height >= 48)
            assertContentEquals(s3SmallPatch(e, testScheduler, face("参考短信")).pixels,
                s3SmallPatch(e, testScheduler, sms).pixels, "实际短信按钮必须绘官方Messages图标而不是笔")
        }
    } }

    @Test fun managementRefreshIconAndPrimaryButtonUseMiuixBlueInBothThemes() = onMotionTestThread { runTest {
        for (dark in listOf(false, true)) MotionTestEnvironment(testScheduler, 420, 900) {
            SoftVoWiFiTheme(dark) { Column {
                ManagerIconAction(MiuixIcons.Refresh, "刷新", ManagerAction.Refresh, {})
                ManagerTextAction("检查引擎", ManagerAction.Refresh, {}, primary = true)
            } }
        }.use { e ->
            s4Frames(e, 20)
            e.scene.render(testScheduler.currentTime * 1_000_000L).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                for (bounds in listOf(s4Nodes(e).single {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("刷新")
                }.boundsInRoot, s4Action(e, "检查引擎").boundsInRoot)) {
                    var blue = 0
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
                        val pixel = bitmap.getColor(x, y)
                        if ((pixel and 255) > (pixel ushr 16 and 255) + 40 && (pixel and 255) > (pixel ushr 8 and 255) + 25) blue++
                    }
                    assertTrue(blue > 30, "实际刷新图标和主按钮均须绘制蓝色，不能只改文案或主题声明：dark=$dark blue=$blue")
                }
            } }
        }
    } }

    @Test fun homeControlIsASwitchAndOnlyBackendStateChangesItsCheckedValue() = onMotionTestThread { runTest {
        val sim = mutableStateOf(SimControlInstance(SimHandle("sim-1"), "SIM 1", SimPresence.ACTIVE_MATCHED,
            0, "合成运营商", "001", "01", EngineLifecycle.STOPPED, canStart = true, canStop = false, canApply = true))
        val active = mutableStateOf(true)
        var starts = 0
        MotionTestEnvironment(testScheduler, 420, 900) { SoftVoWiFiTheme(false) {
            CompositionLocalProvider(LocalAppRouteActive provides active.value) { LazyColumn {
                homeDashboardItems(homeDashboardPresentation(RegistrationUiState.Empty, SimControlUiState.Ready(listOf(sim.value)),
                    EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()), 0),
                    { starts++; sim.value = sim.value.copy(operationPending = true) }, {}, {}, {}, {})
            } }
        } }.use { e ->
            s4Frames(e, 20)
            fun toggle() = s4Nodes(e).single { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            assertEquals(ToggleableState.Off, toggle().config[SemanticsProperties.ToggleableState])
            val old = toggle().config[SemanticsActions.OnClick].action!!
            val edge = toggle().boundsInRoot.center + Offset(0f, 23f)
            assertTrue(edge.y > toggle().boundsInRoot.bottom, "前提：触点在28dp轨道之外、48dp触控区内")
            s4Tap(e, edge); s4Frames(e, 2)
            assertEquals(1, starts)
            assertEquals(ToggleableState.Off, toggle().config[SemanticsProperties.ToggleableState], "不能点击即伪造引擎已启动")
            old(); assertEquals(1, starts, "旧回调不能绕过在途门禁")
            sim.value = sim.value.copy(operationPending = false, lifecycle = EngineLifecycle.RUNNING, canStart = false, canStop = true)
            s4Frames(e, 30)
            assertEquals(ToggleableState.On, toggle().config[SemanticsProperties.ToggleableState])
            val enabledCallback = toggle().config[SemanticsActions.OnClick].action!!
            active.value = false; s4Frames(e, 2); enabledCallback()
            assertEquals(1, starts)
        }
    } }

    @Test fun callDetailOffersSmsInsteadOfCopyWhileKeepingCallback() = onMotionTestThread { runTest {
        val owner = OWNER_A
        val history = CallHistoryState.Ready(listOf(historyWrite(peer = "+12025550123", outcome = CallOutcome.REJECTED,
            endedAtMs = T0 + 1000).toRecord(owner)), owner)
        MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
            CallHistoryDetailScreen(callHistoryGroups(history).single().handle, history, { _, _ -> })
        } }.use { e ->
            s4Frames(e, 20)
            assertTrue(s4HasText(e, "回拨"))
            assertTrue(s4HasText(e, "短信"))
            assertFalse(s4HasText(e, "复制号码"))
        }
    } }
}
