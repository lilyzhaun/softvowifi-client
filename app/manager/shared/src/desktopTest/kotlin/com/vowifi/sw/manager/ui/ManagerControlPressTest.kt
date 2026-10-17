package com.vowifi.sw.manager.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S1 §5.1 的真实控件合同；所有事实由指针、语义几何和实际像素读取，不读取生产源码作断言。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ManagerControlPressTest {
    private enum class IconKind { MANAGER, COMMUNICATION }

    @Test fun managerIconUsesP1SmallScale() = assertIconScale(IconKind.MANAGER)
    @Test fun communicationIconUsesP1SmallScale() = assertIconScale(IconKind.COMMUNICATION)
    @Test fun managerIconHasFortyDpCircularTint() = assertIconTint(IconKind.MANAGER)
    @Test fun communicationIconHasFortyDpCircularTint() = assertIconTint(IconKind.COMMUNICATION)

    @Test fun iconHitTargetsStayAtLeast48DpAndOneTapInvokesOnlyOnce() = onMotionTestThread { runTest {
        for (kind in IconKind.entries) {
            var calls = 0
            iconFixture(kind, testScheduler, onClick = { calls++ }).use { fixture ->
                val before = fixture.actions().single().boundsInRoot
                assertTrue(before.width >= 48f && before.height >= 48f)
                fixture.environment.press(before.center)
                fixture.frames(2)
                val during = fixture.actions().single().boundsInRoot
                println("s1-icon-hit kind=$kind before=$before during=$during calls=$calls")
                assertEquals(0, calls, "按下不执行业务")
                assertEquals(before, during, "绘制缩小不能缩小命中区，也不能叠第二个语义点击")
                fixture.environment.release(before.center)
                fixture.frames(2)
                assertEquals(1, calls)
            }
        }
    } }

    @Test fun disabledIconsDoNotReactToRealPointerInput() = onMotionTestThread { runTest {
        for (kind in IconKind.entries) {
            var calls = 0
            iconFixture(kind, testScheduler, enabled = false, onClick = { calls++ }).use { fixture ->
                val bounds = fixture.subjectBounds()
                val sample = bounds.center + Offset(8f, 0f)
                val rest = fixture.pixel(sample)
                fixture.environment.press(bounds.center)
                fixture.frames(2)
                assertEquals(rest, fixture.pixel(sample), "禁用状态不得产生按压回应")
                fixture.environment.release(bounds.center)
                fixture.frames(2)
                assertEquals(0, calls)
                assertTrue(fixture.haptics.isEmpty())
            }
        }
    } }

    @Test fun iconOuterTouchMarginStillClicksWhileItsFaceIsPressed() = onMotionTestThread { runTest {
        for (kind in IconKind.entries) {
            var calls = 0
            iconFixture(kind, testScheduler, onClick = { calls++ }).use { fixture ->
                val before = fixture.actions().single().boundsInRoot
                val edge = Offset(before.left + .75f, before.center.y)
                fixture.environment.press(edge)
                fixture.frames(2)
                assertEquals(0, calls)
                fixture.environment.release(edge)
                fixture.frames(2)
                println("s1-icon-edge kind=$kind originalBounds=$before point=$edge calls=$calls")
                assertEquals(1, calls, "原 48dp 区域内的真实边缘点按必须成功，不能只检查语义矩形")
            }
        }
    } }

    @Test fun busyAndDisabledTextActionsKeepTheirLayoutAndRejectInput() = onMotionTestThread { runTest {
        for ((enabled, busy) in listOf(false to false, true to true)) {
            var calls = 0
            ManagerPressRenderFixture(testScheduler) {
                ManagerTextAction("合成文字动作", ManagerAction.SaveConfiguration, { calls++ }, enabled = enabled, busy = busy)
            }.use { fixture ->
                val before = fixture.textBounds("合成文字动作")
                val point = fixture.subjectBounds().center
                fixture.environment.press(point)
                fixture.frames(2)
                assertEquals(before, fixture.textBounds("合成文字动作"))
                fixture.environment.release(point)
                fixture.frames(2)
                assertEquals(0, calls)
                assertTrue(fixture.haptics.isEmpty())
            }
        }
    } }

    @Test fun textActionKeeps48DpHitGeometryAndCancelsWithoutInvoking() = onMotionTestThread { runTest {
        var calls = 0
        ManagerPressRenderFixture(testScheduler) {
            ManagerTextAction("合成文字动作", ManagerAction.SaveConfiguration, { calls++ })
        }.use { fixture ->
            val before = fixture.actions().single().boundsInRoot
            assertTrue(before.width >= 48f && before.height >= 48f)
            fixture.environment.press(before.center)
            fixture.frames(2)
            val during = fixture.actions().single().boundsInRoot
            fixture.environment.move(Offset(318f, 238f))
            fixture.frames(2)
            fixture.environment.release(Offset(318f, 238f))
            fixture.frames(2)
            println("s1-text-hit before=$before during=$during calls=$calls")
            assertEquals(0, calls)
            assertTrue(fixture.haptics.isEmpty())
            assertEquals(before, during, "P2 保留原布局，仅绘制层回应")
            assertEquals(before, fixture.actions().single().boundsInRoot)
        }
    } }

    @Test fun textActionScalesItsFilledSurfaceWithoutShrinkingTheHitTarget() = onMotionTestThread { runTest {
        var calls = 0
        ManagerPressRenderFixture(testScheduler) {
            ManagerTextAction("合成填色按钮宽度测量", ManagerAction.SaveConfiguration, { calls++ })
        }.use { fixture ->
            val before = fixture.actions().single().boundsInRoot
            assertTrue(before.width >= 120f, "前提：足够宽的实体按钮可以区分完整表面缩放与仅缩文字")
            val edge = Offset(before.left + 1.5f, before.center.y)
            val canvas = fixture.palette.canvas.toArgb()
            assertTrue(fixture.pixel(edge) != canvas, "前提：原按钮填色确实到达该边缘")
            fixture.environment.press(before.center)
            fixture.frames(2)
            val pressedBounds = fixture.actions().single().boundsInRoot
            val pressedEdge = fixture.pixel(edge)
            fixture.environment.release(before.center)
            fixture.frames(2)
            assertEquals(1, calls, "先证明真实点击命中，才能判定表面绘制")
            assertEquals(before, pressedBounds)
            assertEquals(canvas, pressedEdge, "§5.1 P2：原填色边缘也应缩回，不能只缩文字／内部tint")
        }
    } }

    @Test fun logEntryUsesP3LargeScaleWithoutTintingTheCard() = onMotionTestThread { runTest {
        val summary = "合成日志摘要用于测量卡片绘制层尺寸"
        val entry = OperationalLogEntry("10:00:00", OperationalLogLevel.INFO, OperationalLogKind.ENGINE,
            "合成实例", OperationalLogMessage.ENGINE_STARTED, summary)
        ManagerPressRenderFixture(testScheduler) { LogEntryRow(entry) }.use { fixture ->
            val target = fixture.actions().single().boundsInRoot
            val backgroundPoint = Offset(target.center.x, target.top + 6f)
            val rest = fixture.pixel(backgroundPoint)
            assertTrue(rest != fixture.palette.canvas.toArgb(), "前提：实际卡片表面必须能与画布区分")
            val before = fixture.inkBounds(Color(rest))
            assertTrue(before.width >= 240f, "前提：实际整卡表面足够宽，才能区分 .98 与 .96")
            fixture.environment.press(target.center)
            fixture.frames(2)
            val pressed = fixture.pixel(backgroundPoint)
            println("s1-log-card background=$rest/$pressed")
            assertEquals(rest, pressed, "P3 不叠按压色层")
            val during = fixture.inkBounds(Color(rest))
            println("s1-log-card surfaceBefore=$before surfaceDuring=$during")
            assertEquals(before.width * MotionTokens.ScalePressLarge, during.width, 1f, "P3 整卡是 .98，不是旧通用 .96")
            assertTrue(fixture.haptics.isEmpty(), "尚未抬起，不应执行日志动作")
        }
    } }

    @Test fun quickIconTapKeepsScaleAndTintVisibleTogether() = onMotionTestThread { runTest {
        for (kind in IconKind.entries) {
            var calls = 0
            iconFixture(kind, testScheduler, durationScale = 1f, onClick = { calls++ }).use { fixture ->
                val bounds = fixture.subjectBounds()
                val color = iconColor(kind, fixture)
                val before = fixture.inkBounds(color)
                val restArea = fixture.squareIconArea(color)
                val tintPoint = bounds.center + Offset(16f, 0f)
                val rest = fixture.pixel(tintPoint)
                fixture.environment.press(bounds.center)
                fixture.frames(stepMillis = 8)
                fixture.environment.release(bounds.center)
                assertEquals(1, calls, "业务不等待按压回弹")
                fixture.frames(2)
                val during = fixture.inkBounds(color)
                val duringArea = fixture.squareIconArea(color)
                val tint = fixture.pixel(tintPoint)
                println("s1-quick-icon kind=$kind inkBefore=$before inkDuring=$during tint=$rest/$tint")
                assertTrue(duringArea < restArea, "松手后仍在 90ms 最短可见窗口内")
                assertTrue(tint != rest, "tint 必须与 scale 共享同一个可见态")
                fixture.frames(24)
                assertEquals(before, fixture.inkBounds(color))
                assertEquals(rest, fixture.pixel(tintPoint))
                assertEquals(1, calls)
            }
        }
    } }

    @Test fun inactiveControlsCancelOldTouchesButKeepFreshAndSemanticActionsUsable() = onMotionTestThread { runTest {
        for (control in listOf("manager-icon", "communication-icon", "text", "line")) {
            var active by mutableStateOf(true)
            var calls = 0
            ManagerPressRenderFixture(testScheduler) {
                CompositionLocalProvider(LocalAppRouteActive provides active) {
                    when (control) {
                        "manager-icon" -> ManagerIconAction(ManagerPressTestIcon, "合成图标", ManagerAction.Refresh, { calls++ })
                        "communication-icon" -> CommunicationIconAction(ManagerPressTestIcon, "合成图标", { calls++ })
                        "text" -> ManagerTextAction("合成文字动作", ManagerAction.SaveConfiguration, { calls++ })
                        else -> CommunicationLineChip("合成线路", "选择合成线路", { calls++ })
                    }
                }
            }.use { fixture ->
                val bounds = fixture.actions().single().boundsInRoot
                val point = bounds.center
                fixture.environment.press(point)
                fixture.frames(2)
                active = false
                fixture.frames(2)
                active = true
                fixture.frames(2)
                fixture.environment.release(point)
                fixture.frames(2)
                assertEquals(0, calls, "失活后恢复不得复活旧手指，control=$control")
                assertTrue(fixture.haptics.isEmpty())
                fixture.environment.press(point)
                fixture.frames(2)
                assertEquals(bounds, fixture.actions().single().boundsInRoot, "命中几何始终稳定，control=$control")
                fixture.environment.release(point)
                fixture.frames(2)
                assertEquals(1, calls)
                assertTrue(requireNotNull(fixture.actions().single().config[SemanticsActions.OnClick].action).invoke())
                assertEquals(2, calls, "语义动作仍走真实业务，不用无Press拒绝补丁，control=$control")
            }
        }
    } }

    @Test fun stableLineChipHostKeepsTheOriginalRestingLayout() = onMotionTestThread { runTest {
        val reference = ManagerPressRenderFixture(testScheduler) { LineChipLayoutReference() }.use {
            Triple(it.subjectBounds(), it.textBounds("合成线路"), it.actions().single().boundsInRoot)
        }
        ManagerPressRenderFixture(testScheduler) { CommunicationLineChip("合成线路", "选择合成线路", {}) }.use {
            assertEquals(reference, Triple(it.subjectBounds(), it.textBounds("合成线路"), it.actions().single().boundsInRoot),
                "§5.1：仅拆分稳定命中与绘制层，保留原15/20sp、14dp箭头、8dp间距及8/10dp内距")
        }
    } }

    @Composable private fun LineChipLayoutReference() {
        val interaction = remember { MutableInteractionSource() }
        val color = communicationColors().accent
        Row(
            Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClick = {})
                .semantics { contentDescription = "选择合成线路" }.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("合成线路", Modifier.weight(1f, fill = false), fontSize = 15.sp, lineHeight = 20.sp, color = color)
            Icon(CommunicationChevronDown, null, Modifier.size(14.dp), tint = color)
        }
    }

    private fun assertIconScale(kind: IconKind) = onMotionTestThread { runTest {
        iconFixture(kind, testScheduler).use { fixture ->
            val color = iconColor(kind, fixture)
            val before = fixture.inkBounds(color)
            assertEquals(24f, before.width, .1f, "前提：实际渲染的是 24dp 测试图标")
            val beforeArea = fixture.squareIconArea(color)
            fixture.environment.press(fixture.subjectBounds().center)
            fixture.frames(2)
            val during = fixture.inkBounds(color)
            val duringArea = fixture.squareIconArea(color)
            val scale = sqrt(duringArea / beforeArea)
            println("s1-icon-scale kind=$kind before=$before during=$during area=$beforeArea/$duringArea scale=$scale")
            assertEquals(MotionTokens.ScalePressSmall, scale, .015f, "P1 图标必须 .88，而不是 .96；计入抗锯齿覆盖率")
        }
    } }

    private fun assertIconTint(kind: IconKind) = onMotionTestThread { runTest {
        iconFixture(kind, testScheduler).use { fixture ->
            val center = fixture.subjectBounds().center
            val inside = center + Offset(16f, 0f)
            val outside = center + Offset(21f, 0f)
            val corner = center + Offset(17f, 17f)
            val restInside = fixture.pixel(inside)
            val restOutside = fixture.pixel(outside)
            val restCorner = fixture.pixel(corner)
            fixture.environment.press(center)
            fixture.frames(2)
            val pressed = fixture.pixel(inside)
            val expected = fixture.palette.textPrimary.copy(alpha = MotionTokens.TintPress).compositeOver(Color(restInside)).toArgb()
            println("s1-icon-tint kind=$kind rest=$restInside pressed=$pressed expected=$expected")
            assertColorNear(expected, pressed)
            assertEquals(restOutside, fixture.pixel(outside), "40dp 色层不能扩张到整个 48dp 命中区")
            assertEquals(restCorner, fixture.pixel(corner), "色层必须是圆形，不是矩形")
        }
    } }

    private fun iconFixture(kind: IconKind, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        enabled: Boolean = true, durationScale: Float = 0f, onClick: () -> Unit = {}) =
        ManagerPressRenderFixture(scheduler, durationScale) {
            when (kind) {
                IconKind.MANAGER -> ManagerIconAction(ManagerPressTestIcon, "合成图标", ManagerAction.Refresh, onClick, enabled = enabled)
                IconKind.COMMUNICATION -> CommunicationIconAction(ManagerPressTestIcon, "合成图标", onClick, enabled)
            }
        }

    private fun iconColor(kind: IconKind, fixture: ManagerPressRenderFixture) = when (kind) {
        IconKind.MANAGER -> fixture.palette.interactive
        IconKind.COMMUNICATION -> fixture.communicationAccent
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        for (shift in listOf(24, 16, 8, 0)) {
            assertTrue(abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 2,
                "按压色层必须是文字主色的 8%，expected=$expected actual=$actual")
        }
    }
}
