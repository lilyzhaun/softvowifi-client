package com.vowifi.sw.manager.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ManagerPreferencePressTest {
    private val title = "合成设置导航"
    private val row get() = managerNavigatePreference(title, "仅用于合成测试的摘要", ManagerPressTestIcon)

    @Test fun desktopZeroDelayPressUsesP4AndScrollTakeoverCancelsAndRestoresDrawing() = onMotionTestThread { runTest {
        var calls = 0
        val scroll = ScrollState(0)
        ManagerPressRenderFixture(testScheduler, scroll = scroll) {
            // Keep the title in the viewport while reading the actual scroll-cancellation frame.
            Column {
                Spacer(Modifier.height(48.dp))
                ManagerNavigatePreferenceItem(row, { calls++ })
            }
        }.use { fixture ->
            val region = fixture.textBounds(title).inflate(6f)
            val before = fixture.inkBounds(fixture.preferenceTitleColor, region)
            val beforeSpan = fixture.textInkSpan(title)
            val bounds = fixture.actions().single().boundsInRoot
            val start = Offset(bounds.center.x, bounds.bottom - 12f)
            val downAt = testScheduler.currentTime
            fixture.environment.press(start)
            fixture.frames(stepMillis = 8)
            val early = fixture.inkBounds(fixture.preferenceTitleColor, region)
            val earlySpan = fixture.textInkSpan(title)
            val earlyAt = testScheduler.currentTime - downAt
            fixture.environment.move(start - Offset(0f, 24f))
            fixture.frames(stepMillis = 8)
            fixture.environment.move(start - Offset(0f, 48f))
            fixture.frames(stepMillis = 8)
            val restored = fixture.inkBounds(fixture.preferenceTitleColor, fixture.textBounds(title).inflate(6f))
            fixture.environment.release(start - Offset(0f, 48f))
            fixture.frames(2)
            println("s1-preference-scroll earlyMs=$earlyAt before=$before early=$early restored=$restored scroll=${scroll.value} calls=$calls")
            assertTrue(scroll.value > 0, "必须先证明真实列表已经接管，前提失败不算视觉缺陷复现")
            assertEquals(0, calls)
            assertTrue(fixture.haptics.isEmpty(), "滚动取消不发触感")
            // §5.1 follows the real clickable source: pinned Desktop delay is 0, Android delay needs device proof.
            assertTrue(beforeSpan * (1f - MotionTokens.ScalePress) > .8f, "采样跨度必须足以用原.8px容差拒绝未缩放")
            assertEquals(beforeSpan * MotionTokens.ScalePress, earlySpan, .8f, "桌面零延迟真实Press仍是P4 .96，不额外添加人工延迟")
            assertEquals(before.width, restored.width, "实际滚动接管应取消按压并恢复绘制，不维持成功Release的90ms窗口")
        }
    } }

    @Test fun preferenceContentRetainsP4ScaleWithoutPaintingALargeFill() = onMotionTestThread { runTest {
        ManagerPressRenderFixture(testScheduler) { ManagerNavigatePreferenceItem(row, {}) }.use { fixture ->
            val region = fixture.textBounds(title).inflate(6f)
            val before = fixture.inkBounds(fixture.preferenceTitleColor, region)
            val beforeSpan = fixture.textInkSpan(title)
            val target = fixture.subjectBounds()
            val blank = Offset(target.center.x, target.top + 6f)
            val rest = fixture.pixel(blank)
            fixture.environment.press(target.center)
            fixture.frames(2)
            val during = fixture.inkBounds(fixture.preferenceTitleColor, region)
            val duringSpan = fixture.textInkSpan(title)
            println("s1-preference-content before=$before during=$during background=$rest/${fixture.pixel(blank)}")
            assertTrue(beforeSpan * (1f - MotionTokens.ScalePress) > .8f, "采样跨度必须足以用原.8px容差拒绝未缩放")
            assertEquals(beforeSpan * MotionTokens.ScalePress, duringSpan, .8f, "P4 保留 .96 内容回应")
            assertEquals(rest, fixture.pixel(blank), "用户拒绝的大面积灰块不能恢复")
        }
    } }

    @Test fun preferenceHitGeometryStaysFixedAndMovingOutsideCancelsIt() = onMotionTestThread { runTest {
        var calls = 0
        ManagerPressRenderFixture(testScheduler) { ManagerNavigatePreferenceItem(row, { calls++ }) }.use { fixture ->
            val before = fixture.actions().single().boundsInRoot
            assertTrue(before.width >= 48f && before.height >= 48f)
            fixture.environment.press(before.center)
            fixture.frames(2)
            val during = fixture.actions().single().boundsInRoot
            fixture.environment.move(Offset(318f, 238f))
            fixture.frames(2)
            fixture.environment.release(Offset(318f, 238f))
            fixture.frames(2)
            println("s1-preference-hit before=$before during=$during calls=$calls")
            assertEquals(0, calls)
            assertTrue(fixture.haptics.isEmpty())
            assertEquals(before, during, "行内容缩放不能缩小行的命中几何")
            assertEquals(before, fixture.actions().single().boundsInRoot)
        }
    } }

    @Test fun toggleRowAndInnerSwitchEachPerformExactlyOneRealToggle() = onMotionTestThread { runTest {
        var checked by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        ManagerPressRenderFixture(testScheduler) {
            ManagerTogglePreferenceItem(managerTogglePreference(AppearancePreferenceKey.BLUR_ENABLED,
                "合成开关", "仅用于合成测试的摘要", ManagerPressTestIcon, checked), { checked = it; changes += it })
        }.use { fixture ->
            val body = fixture.subjectBounds().let { Offset(it.left + 32f, it.center.y) }
            fixture.environment.press(body)
            fixture.frames(2)
            assertTrue(changes.isEmpty())
            fixture.environment.release(body)
            fixture.frames(2)
            assertEquals(listOf(true), changes, "行本身与内开关是两个合法目标，不应叠第二次业务调用")
            assertEquals(1, fixture.haptics.size, "行只执行应用handler的一次触感")
            val thumb = fixture.nodes().single {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                    it.config.contains(SemanticsProperties.ToggleableState) && it.config.contains(SemanticsActions.OnClick)
            }.boundsInRoot.center
            fixture.environment.press(thumb)
            fixture.frames(2)
            fixture.environment.release(thumb)
            fixture.frames(2)
            println("s1-toggle-callbacks changes=$changes")
            assertEquals(listOf(true, false), changes)
            assertEquals(2, fixture.haptics.size, "内Switch不重复发触感，应用handler不能被抑制")
        }
    } }

    @Test fun disabledAndBusyPreferencesDoNotReactOrInvoke() = onMotionTestThread { runTest {
        for (availability in listOf(ManagerPreferenceAvailability.Disabled("合成禁用原因"), ManagerPreferenceAvailability.Busy)) {
            for (toggle in listOf(false, true)) {
                var calls = 0
                ManagerPressRenderFixture(testScheduler) {
                    if (toggle) ManagerTogglePreferenceItem(managerTogglePreference(AppearancePreferenceKey.BLUR_ENABLED,
                        title, "合成说明", ManagerPressTestIcon, false, availability), { calls++ })
                    else ManagerNavigatePreferenceItem(managerNavigatePreference(title, "合成说明", ManagerPressTestIcon, availability), { calls++ })
                }.use { fixture ->
                    val before = fixture.textBounds(title)
                    val target = fixture.subjectBounds().center
                    fixture.environment.press(target)
                    fixture.frames(2)
                    assertEquals(before, fixture.textBounds(title))
                    fixture.environment.release(target)
                    fixture.frames(2)
                    assertEquals(0, calls)
                    assertTrue(fixture.haptics.isEmpty())
                }
            }
        }
    } }

    @Test fun publicSwitchCompositionMatchesTheLibrarySlotGeometryAndSemantics() = onMotionTestThread { runTest {
        // §5.1：仅重接行源，P6控件及原8dp尾槽／role／字号几何必须与已安装0.9.3相同。
        val cases = listOf(
            SwitchCase(false, ManagerPreferenceAvailability.Enabled),
            SwitchCase(true, ManagerPreferenceAvailability.Enabled, 2f),
            SwitchCase(false, ManagerPreferenceAvailability.Disabled("合成禁用原因")),
            SwitchCase(false, ManagerPreferenceAvailability.Busy),
            SwitchCase(false, ManagerPreferenceAvailability.Error("合成错误说明")),
        )
        for (case in cases) {
            val copy = if (case.fontScale > 1f) "合成设置开关与较长中文标题" else "合成开关"
            val row = managerTogglePreference(AppearancePreferenceKey.BLUR_ENABLED, copy, "合成开关摘要",
                ManagerPressTestIcon, case.checked, case.availability)
            val reference = ManagerPressRenderFixture(testScheduler, fontScale = case.fontScale) { NativeSwitchReference(row) }.use {
                it.subjectBounds() to semanticGeometry(it)
            }
            ManagerPressRenderFixture(testScheduler, fontScale = case.fontScale) { ManagerTogglePreferenceItem(row, {}) }.use {
                assertEquals(reference.first, it.subjectBounds(), "原整行约束及8dp尾槽不变，case=$case")
                assertEquals(reference.second, semanticGeometry(it), "原title/summary/role/P6开关/禁用/进度语义及几何不变，case=$case")
            }
        }
    } }

    @Test fun compiledPreferenceAssemblyUsesPublicBasicComponentWithoutTheSwitchPreferenceWrapper() {
        val symbols = requireNotNull(javaClass.getResourceAsStream("/com/vowifi/sw/manager/ui/ManagerPreferenceItemsKt.class"))
            .use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        // §5.1：编译产物与实际几何／P6回调合同共同守护，不靠源码注释冒充组合接线。
        assertTrue(symbols.contains("ManagerTogglePreferenceItem"))
        assertTrue(symbols.contains("top/yukonga/miuix/kmp/basic/ComponentKt"))
        assertFalse(symbols.contains("top/yukonga/miuix/kmp/preference/SwitchPreferenceKt"))
    }

    @Test fun inactiveArrowCannotExecuteOrReviveAnOldTouch() = onMotionTestThread { runTest {
        var active by mutableStateOf(true)
        var calls = 0
        ManagerPressRenderFixture(testScheduler) {
            CompositionLocalProvider(LocalAppRouteActive provides active) { ManagerNavigatePreferenceItem(row, { calls++ }) }
        }.use { fixture ->
            val point = fixture.subjectBounds().center
            fixture.environment.press(point)
            fixture.frames(2)
            active = false
            fixture.frames(2)
            active = true
            fixture.frames(2)
            fixture.environment.release(point)
            fixture.frames(2)
            assertEquals(0, calls, "失活必须取消真实点击，恢复不能复活旧手指")
            assertTrue(fixture.haptics.isEmpty())
            fixture.environment.press(point)
            fixture.frames(2)
            fixture.environment.release(point)
            fixture.frames(2)
            assertEquals(1, calls, "恢复后的新点击仍可用，不按固定时间防抖")
            assertTrue(requireNotNull(fixture.actions().single().config[SemanticsActions.OnClick].action).invoke())
            assertEquals(2, calls, "正常无障碍语义动作不能因没有原始Press而被拒绝")
        }
    } }

    private data class SwitchCase(val checked: Boolean, val availability: ManagerPreferenceAvailability, val fontScale: Float = 1f)

    private data class NodeGeometry(
        val text: List<String>, val role: Role?, val toggle: ToggleableState?, val clickable: Boolean,
        val disabled: Boolean, val progress: ProgressBarRangeInfo?, val bounds: Rect,
    )

    private fun semanticGeometry(fixture: ManagerPressRenderFixture): List<NodeGeometry> = fixture.nodes().mapNotNull { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val role = node.config.getOrNull(SemanticsProperties.Role)
        val toggle = node.config.getOrNull(SemanticsProperties.ToggleableState)
        val clickable = node.config.contains(SemanticsActions.OnClick)
        val disabled = node.config.contains(SemanticsProperties.Disabled)
        val progress = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        if (text.isEmpty() && role == null && toggle == null && !clickable && !disabled && progress == null) null
        else NodeGeometry(text, role, toggle, clickable, disabled, progress, node.boundsInRoot)
    }

    @Composable private fun NativeSwitchReference(row: ManagerPreferenceRow.Toggle) {
        val colors = LocalSoftVoWiFiColors.current
        val summary = if (row.isErrorState) BasicComponentDefaults.summaryColor(colors.error, colors.error)
            else BasicComponentDefaults.summaryColor()
        CompositionLocalProvider(LocalIndication provides NoVisualIndication) {
            SwitchPreference(
                checked = row.checked, onCheckedChange = {}, title = row.title, summary = row.visibleSummary,
                summaryColor = summary, enabled = row.actionable,
                startAction = { ManagerPreferenceStartIcon(row.startIcon, row.availability) },
                modifier = Modifier.defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget).semantics {
                    if (row.showsProgress) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
            )
        }
    }
}
