@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** 相邻根页保留绘制不等于保留输入；只采固定原位置的真实本地矢量与按钮表面。 */
class CommunicationRootFabRetentionRenderTest {
    @Test fun callsAndMessagesRetainFabPixelsAcrossRapidRouteReversalsWithoutRetainingActions() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 45.seconds) {
            val retentionFailures = mutableListOf<String>()
            for (messages in listOf(false, true)) {
                val label = if (messages) "新建短信" else "拨号盘"
                var active by mutableStateOf(true)
                var businessShow by mutableStateOf(true)
                var backendCalls = 0
                MotionTestEnvironment(testScheduler, 390, 844, dispatcher = dispatcher) {
                    SoftVoWiFiTheme(false) {
                        CompositionLocalProvider(LocalAppRouteActive provides active) {
                            PhoneStyle {
                                if (messages) MessagePageScaffold(
                                    onNew = { backendCalls++ }, showNew = businessShow,
                                    searching = false, query = "", onSearch = {}, onQuery = {}, onCloseSearch = {},
                                ) {} else PhoneHistoryScaffold(
                                    searching = false, query = "", onSearch = {}, onQuery = {}, onCloseSearch = {},
                                    showDial = businessShow, onDial = { backendCalls++ },
                                ) {}
                            }
                        }
                    }
                }.use { e ->
                    fun frames(count: Int) { repeat(count) { e.advanceFrameBy() } }
                    fun action() = s4Action(e, label).config[SemanticsActions.OnClick].action!!
                    frames(40)
                    val face = s4Nodes(e).single {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "phone-face-$label"
                    }.boundsInRoot
                    assertEquals(64f, face.width, .01f, "前提：真实64dp右下入口")
                    assertEquals(64f, face.height, .01f)
                    assertTrue(face.left > 250f && face.top > 650f, "前提：采样的是右下入口而非header")
                    // 56×56固定原矩形：含真实白色图标和有色表面，不随退出语义消失重新定位。
                    val region = Rect(face.left + 4f, face.top + 4f, face.right - 4f, face.bottom - 4f)
                    fun patch() = s3SmallPatch(e, testScheduler, region)
                    val baseline = patch()
                    val surface = 0xff376f60.toInt()
                    val ink = 0xffffffff.toInt()
                    fun surfaceCount(p: S3SmallPatch) = p.pixels.count { s3RgbDistance(it, surface) <= 2 }
                    fun inkCount(p: S3SmallPatch) = p.pixels.count { s3RgbDistance(it, ink) <= 2 }
                    assertTrue(surfaceCount(baseline) > 2000, "前提：确实采到有色按钮表面")
                    assertTrue(inkCount(baseline) > 20, "前提：确实采到本地矢量白色墨迹，非浅色canvas")
                    println("FAB_RETENTION_BASELINE|label=$label|face=$face|region=$region|surface=${surfaceCount(baseline)}|ink=${inkCount(baseline)}")
                    val oldAction = action()
                    val trajectory = mutableListOf<String>()
                    // 每次状态交接仅推进一帧；不等待旧退出停稳，不用终值代替快速反向。
                    repeat(6) { reversal ->
                        for (nextActive in listOf(false, true)) {
                            active = nextActive
                            e.advanceFrameBy()
                            val current = patch()
                            val diff = s4Difference(baseline, current)
                            trajectory += "${reversal + 1}:$nextActive:$diff:${surfaceCount(current)}:${inkCount(current)}"
                            if (diff != 0) retentionFailures += "$label reversal=${reversal + 1} active=$nextActive diff=$diff surface=${surfaceCount(current)} ink=${inkCount(current)}"
                            if (!nextActive) {
                                oldAction.invoke()
                                assertEquals(0, backendCalls, "旧action在inactive首帧必须撤权")
                                assertTrue(s4Actions(e, label).isEmpty(), "inactive入口不得暴露当前动作语义")
                            } else {
                                action() // 恢复当前语义不等于允许旧inactive action穿透。
                            }
                        }
                    }
                    println("FAB_RETENTION_REVERSALS|label=$label|samples=$trajectory|inactiveBackend=$backendCalls")
                    frames(40)
                    assertTrue(action().invoke())
                    assertEquals(1, backendCalls, "恢复active后真实动作只执行一次")
                    val businessAction = action()
                    businessShow = false
                    val exit = List(8) {
                        e.advanceFrameBy()
                        assertTrue(s4Actions(e, label).isEmpty(), "真实业务隐藏首帧即撤销动作")
                        businessAction.invoke()
                        assertEquals(1, backendCalls, "业务隐藏不能缓存旧action权限")
                        patch()
                    }
                    frames(40)
                    val absent = patch()
                    assertEquals(0, surfaceCount(absent), "业务隐藏终态确实无按钮表面")
                    assertEquals(0, inkCount(absent), "业务隐藏终态确实无白色矢量墨迹")
                    assertTrue(s4Actions(e, label).isEmpty())
                    assertTrue(exit.any { s4Difference(it, baseline) > 2 && s4Difference(it, absent) > 2 },
                        "前提：真实业务隐藏仍有原spring退出中间绘制")
                    businessShow = true
                    val enter = List(8) { e.advanceFrameBy(); patch() }
                    frames(40)
                    val restored = patch()
                    assertEquals(0, s4Difference(baseline, restored), "真实业务再入场终态恢复原图标与材质")
                    assertTrue(enter.any { s4Difference(it, absent) > 2 && s4Difference(it, restored) > 2 },
                        "前提：真实业务再入场有非终值中帧，不能移除动画")
                    action()
                    println("FAB_RETENTION_GATES|label=$label|activeBackend=$backendCalls|absentSurface=${surfaceCount(absent)}|absentInk=${inkCount(absent)}|exitDiff=${exit.map { s4Difference(it, baseline) }}|enterDiff=${enter.map { s4Difference(it, restored) }}|restoredDiff=${s4Difference(baseline, restored)}|prerequisites=PASS")
                }
            }
            // 两个真实scaffold的前提、旧动作撤权、业务动画全部通过后，才判行为RED。
            assertTrue(retentionFailures.isEmpty(), "根页active交接不应重播右下入口退出/进入：\n${retentionFailures.joinToString("\n")}")
        }
    }
}
