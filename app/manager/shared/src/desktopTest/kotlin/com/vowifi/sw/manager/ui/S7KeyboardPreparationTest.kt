@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlinx.coroutines.test.runTest
import kotlin.math.floor
import kotlin.test.*

// 复用现ManagerPressRenderFixture，真实键盘/绘制合同；迁入后仍须统筹统一执行。
class S7KeyboardPreparationTest {
    @Test fun actualTabFocusHasTwoDpInkAndEnterUsesTheOriginalPressAndSingleActionIncludingZeroAndDisabled() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) {
            var enabled by mutableStateOf(true); var calls = 0
            ManagerPressRenderFixture(testScheduler, durationScale = scale) {
                ManagerTextAction("合成操作", ManagerAction.ContextAction, { calls++ },
                    modifier = Modifier.size(96.dp, 48.dp), enabled = enabled)
            }.use { f ->
                val e = f.environment; val keyboard = Canvas()
                fun consumeNow() { repeat(4) { e.advanceFrameBy(0) } }
                fun button() = f.actions().single()
                fun key(key: Key, code: Int, down: Boolean): Boolean = e.scene.sendKeyEvent(KeyEvent(
                    key, if (down) KeyEventType.KeyDown else KeyEventType.KeyUp, nativeEvent = AwtKeyEvent(
                        keyboard, if (down) AwtKeyEvent.KEY_PRESSED else AwtKeyEvent.KEY_RELEASED,
                        testScheduler.currentTime, 0, code, AwtKeyEvent.CHAR_UNDEFINED)))
                consumeNow(); val bounds = button().boundsInRoot
                assertEquals(96f, bounds.width); assertEquals(48f, bounds.height)
                assertEquals(floor(bounds.left), bounds.left); assertEquals(floor(bounds.top), bounds.top)
                assertTrue(bounds.left >= 1f && bounds.right <= 320f && bounds.top >= 1f && bounds.bottom <= 240f)
                assertFalse(button().config.getOrNull(SemanticsProperties.Focused) == true)
                // Density(1f)来自原fixture：顶边中央避圆角/文字；外1px、内2px及内侧2px共16×5。
                val edge = Rect(bounds.center.x - 8f, bounds.top - 1f, bounds.center.x + 8f, bounds.top + 4f)
                val beforeFocus = s3SmallPatch(e, testScheduler, edge)
                assertTrue(key(Key.Tab, AwtKeyEvent.VK_TAB, true), "真实Tab须经场景焦点系统消费")
                key(Key.Tab, AwtKeyEvent.VK_TAB, false); consumeNow(); f.frames(16); consumeNow()
                assertTrue(button().config.getOrNull(SemanticsProperties.Focused) == true, "先证明公开Focused，不能手发FocusInteraction")
                assertEquals(bounds, button().boundsInRoot, "focus不能改变布局或命中矩形")
                val focusedEdge = s3SmallPatch(e, testScheduler, edge)
                val accent = f.managerAccent.toArgb()
                assertTrue(s3RgbDistance(beforeFocus.pixels[8], accent) >= 48, "描边必须与已知原背景可区分")
                val glyph = f.textBounds("合成操作").inflate(1f)
                assertTrue(glyph.left > bounds.left + 2 && glyph.top > bounds.top + 2 &&
                    glyph.right < bounds.right - 2 && glyph.bottom < bounds.bottom - 2, "按压采样只含自然完整字形，不含focus-ring")
                val resting = s3SmallPatch(e, testScheduler, glyph)
                f.haptics.clear(); val downConsumed = key(Key.Enter, AwtKeyEvent.VK_ENTER, true)
                val downAt = testScheduler.currentTime
                consumeNow(); if (scale != 0f) f.frames(4)
                val held = s3SmallPatch(e, testScheduler, glyph)
                if (scale == 0f) assertEquals(downAt, testScheduler.currentTime, "零动画按下同刻取样，不推迟到64ms")
                assertTrue(downConsumed, "Enter KeyDown必须命中原控件")
                assertEquals(0, calls, "KeyDown只显示按压，不提前提交")
                assertTrue(s4Difference(resting, held) >= 4, "真实Enter持有须产生原按压绘制；不读内部动画值")
                assertEquals(bounds, button().boundsInRoot, "press只改绘制，不缩命中")
                assertTrue(key(Key.Enter, AwtKeyEvent.VK_ENTER, false)); consumeNow()
                assertEquals(1, calls, "真实KeyUp恰一次业务")
                assertEquals(listOf(HapticFeedbackType.ContextClick), f.haptics)
                f.frames(32); consumeNow()
                assertTrue(resting.pixels.contentEquals(s3SmallPatch(e, testScheduler, glyph).pixels), "松手后自然字形回原值")
                enabled = false; consumeNow(); f.frames(16); consumeNow()
                assertTrue(button().config.contains(SemanticsProperties.Disabled))
                assertFalse(button().config.getOrNull(SemanticsProperties.Focused) == true)
                val disabled = s3SmallPatch(e, testScheduler, bounds)
                f.haptics.clear(); key(Key.Tab, AwtKeyEvent.VK_TAB, true); key(Key.Tab, AwtKeyEvent.VK_TAB, false)
                key(Key.Enter, AwtKeyEvent.VK_ENTER, true); consumeNow(); f.frames(4)
                key(Key.Enter, AwtKeyEvent.VK_ENTER, false); consumeNow(); f.frames(16)
                assertEquals(1, calls); assertTrue(f.haptics.isEmpty(), "disabled键盘不执行/不发触感")
                assertTrue(disabled.pixels.contentEquals(s3SmallPatch(e, testScheduler, bounds).pixels))
                assertEquals(bounds, button().boundsInRoot)
                // 后置颜色断言，焦点描边缺失不会阻止本方法先采到真实Enter/disabled行为。
                assertEquals(16, focusedEdge.width); assertEquals(5, focusedEdge.height)
                for (y in 0 until 5) for (x in 0 until 16) {
                    val i = y * 16 + x
                    if (y == 1 || y == 2) assertTrue(s3RgbDistance(focusedEdge.pixels[i], accent) <= 8,
                        "S7：Density1的顶边内两行须为真实2dp强调色，scale=$scale row=$y")
                    else assertTrue(s3RgbDistance(focusedEdge.pixels[i], beforeFocus.pixels[i]) <= 2,
                        "2dp以外边缘须保持原背景，不能用更粗环蒙混，scale=$scale row=$y")
                }
            }
        }
    } }

    @Test fun realTabMatrixUsesOwnFocusAndWithdrawsRingForTouchAndInactiveRoutesWithoutRelayout() = onMotionTestThread { runTest {
        val failures = mutableListOf<String>(); val terminals = mutableMapOf<Int, IntArray>()
        for (kind in 0..8) for (scale in listOf(1f, 0f)) {
            var enabled by mutableStateOf(true); var active by mutableStateOf(true); var calls = 0
            var mode: InputModeManager? = null; var accent = 0
            ManagerPressRenderFixture(testScheduler, durationScale = scale) {
                val manager = LocalInputModeManager.current
                val ink = when (kind) { 0 -> top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary; 2 -> phoneColors().accent
                    8 -> phoneColors().onAccent; else -> communicationColors().accent }
                SideEffect { mode = manager; accent = ink.toArgb() }
                CompositionLocalProvider(LocalAppRouteActive provides active) {
                    when (kind) {
                        0 -> ManagerIconAction(CallPhoneIcons.Phone, "合成焦点", ManagerAction.ContextAction, { calls++ }, enabled = enabled)
                        1 -> CommunicationIconAction(CallPhoneIcons.Phone, "合成焦点", { calls++ }, enabled = enabled)
                        2 -> PhoneButton("合成焦点", { calls++ }, icon = CallPhoneIcons.Phone, enabled = enabled, material = false)
                        3 -> CommunicationTextAction("合成焦点", { calls++ }, enabled = enabled)
                        4 -> CommunicationLineChip("合成焦点", "合成焦点", { calls++ }, enabled = enabled)
                        5 -> CommunicationMenuRow("合成焦点", CallPhoneIcons.Phone, { calls++ }, enabled = enabled)
                        6 -> CommunicationLineRow("合成焦点", "合成说明", false, { calls++ }, enabled = enabled)
                        // As in its production composer, the caller supplies the route/editing gate.
                        7 -> CommunicationInput("", { calls++ }, "合成焦点", enabled = enabled && active, singleLine = true)
                        else -> PhoneButton("合成焦点", { calls++ }, icon = CallPhoneIcons.Phone, enabled = enabled,
                            material = false, prominent = true)
                    }
                }
            }.use { f ->
                val e = f.environment; val keyboard = Canvas(); val context = "kind=$kind scale=$scale"
                fun consumeNow() { repeat(4) { e.advanceFrameBy(0) } }
                fun button(): SemanticsNode {
                    val actions = f.actions()
                    assertEquals(1, actions.size, "前提：真实fixture必须只有一个可点击控件：$context")
                    return actions.single()
                }
                fun tab() {
                    for (down in listOf(true, false)) e.scene.sendKeyEvent(KeyEvent(Key.Tab,
                        if (down) KeyEventType.KeyDown else KeyEventType.KeyUp, nativeEvent = AwtKeyEvent(keyboard,
                            if (down) AwtKeyEvent.KEY_PRESSED else AwtKeyEvent.KEY_RELEASED,
                            testScheduler.currentTime, 0, AwtKeyEvent.VK_TAB, AwtKeyEvent.CHAR_UNDEFINED)))
                    consumeNow()
                }
                consumeNow(); val bounds = button().boundsInRoot
                assertFalse(button().config.getOrNull(SemanticsProperties.Focused) == true)
                assertTrue(bounds.width >= 48f && bounds.height >= 48f && bounds.top >= 1f && bounds.bottom <= 240f)
                // 顶中4px×5px避开中央Icon/标签；圆弧只要求部分新墨迹，不要求两整行均实心。
                val region = Rect(bounds.center.x - 2f, bounds.top - 1f, bounds.center.x + 2f, bounds.top + 4f)
                fun patch() = s3SmallPatch(e, testScheduler, region)
                fun ink(p: S3SmallPatch) = p.pixels.count { s3RgbDistance(it, accent) <= 8 }
                val before = patch(); assertEquals(0, ink(before), "前提：真实外沿原无主题accent，不把Icon墨迹当环：$context")
                tab(); assertTrue(button().config.getOrNull(SemanticsProperties.Focused) == true, "真实Tab先取得原ownSource焦点：$context")
                assertEquals(InputMode.Keyboard, assertNotNull(mode).inputMode, "前提：真实键入须进入Keyboard，不由测试强设")
                assertEquals(bounds, button().boundsInRoot); val focused = patch()
                if (ink(focused) < 2 || s4Difference(before, focused) < 2) failures += "$context：Focused后原外沿缺实际描边墨迹"
                if (scale == 1f) terminals[kind] = focused.pixels else
                    if (!terminals.getValue(kind).contentEquals(focused.pixels)) failures += "$context：zero focus环终值不同"
                val blank = androidx.compose.ui.geometry.Offset(280f, 200f)
                assertFalse(bounds.contains(blank)); e.press(blank); e.release(blank); consumeNow()
                // ImageComposeScene指针不自动切输入模式；真实指针后用同一公开manager驱动桌面平台设备切换。
                // 不是注入FocusInteraction，不冒充Android自动切换；Keyboard仍只由上方真实Tab取得。
                assertTrue(assertNotNull(mode).requestInputMode(InputMode.Touch), "前提：桌面平台公开Touch模式请求必须成功：$context")
                consumeNow()
                assertEquals(InputMode.Touch, assertNotNull(mode).inputMode, "前提：真实Touch需切回Touch模式，不假注FocusInteraction")
                if (ink(patch()) != 0) failures += "$context：Touch模式仍画键盘环"
                tab(); assertTrue(button().config.getOrNull(SemanticsProperties.Focused) == true)
                active = false; consumeNow()
                assertTrue(button().config.contains(SemanticsProperties.Disabled))
                if (ink(patch()) != 0) failures += "$context：route失活未撤焦点环"
                enabled = false; consumeNow(); assertEquals(bounds, button().boundsInRoot)
                if (ink(patch()) != 0) failures += "$context：disabled仍有键盘环"
                assertEquals(0, calls); assertTrue(f.haptics.isEmpty(), "Tab/空白Touch/门禁变化不执行业务或触感")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    } }

    @Test fun realPreferenceTabFocusDrawsInsideTheLibraryOwnedRowAndWithdrawsWithoutAnotherClickOwner() = onMotionTestThread { runTest {
        val failures = mutableListOf<String>()
        for (scale in listOf(1f, 0f)) {
            var active by mutableStateOf(true); var calls = 0; var accent = 0
            var mode: InputModeManager? = null
            ManagerPressRenderFixture(testScheduler, durationScale = scale) {
                val manager = LocalInputModeManager.current
                val ink = LocalSoftVoWiFiColors.current.interactive
                SideEffect { mode = manager; accent = ink.toArgb() }
                CompositionLocalProvider(LocalAppRouteActive provides active) {
                    ManagerNavigatePreferenceItem(managerNavigatePreference("合成焦点", "合成说明", CallPhoneIcons.Phone), { calls++ })
                }
            }.use { f ->
                val e = f.environment; val keyboard = Canvas()
                fun consumeNow() { repeat(4) { e.advanceFrameBy(0) } }
                fun tab() {
                    for (down in listOf(true, false)) e.scene.sendKeyEvent(KeyEvent(Key.Tab,
                        if (down) KeyEventType.KeyDown else KeyEventType.KeyUp, nativeEvent = AwtKeyEvent(keyboard,
                            if (down) AwtKeyEvent.KEY_PRESSED else AwtKeyEvent.KEY_RELEASED,
                            testScheduler.currentTime, 0, AwtKeyEvent.VK_TAB, AwtKeyEvent.CHAR_UNDEFINED)))
                    consumeNow()
                }
                consumeNow(); assertEquals(1, f.actions().size, "原ArrowPreference只能有一个点击所有者")
                val row = f.actions().single(); val bounds = row.boundsInRoot
                assertTrue(bounds.width >= 48f && bounds.height >= 48f && bounds.top >= 1 && bounds.bottom <= 240)
                val region = Rect(bounds.center.x - 2f, bounds.top - 1f, bounds.center.x + 2f, bounds.top + 4f)
                fun patch() = s3SmallPatch(e, testScheduler, region)
                fun ink(p: S3SmallPatch) = p.pixels.count { s3RgbDistance(it, accent) <= 8 }
                val before = patch(); assertEquals(0, ink(before))
                tab(); val focused = f.actions().single()
                assertTrue(focused.config.getOrNull(SemanticsProperties.Focused) == true, "前提：真实Tab须聚焦库原行")
                assertEquals(InputMode.Keyboard, assertNotNull(mode).inputMode)
                assertEquals(bounds, focused.boundsInRoot)
                if (ink(patch()) < 2) failures += "P4 scale=$scale：库原InteractionSource聚焦后缺强调色内描边"
                assertTrue(assertNotNull(mode).requestInputMode(InputMode.Touch)); consumeNow()
                if (ink(patch()) != 0) failures += "P4 scale=$scale：Touch模式未撤环"
                tab(); active = false; consumeNow()
                if (ink(patch()) != 0) failures += "P4 scale=$scale：失活未撤环"
                assertEquals(0, calls); assertTrue(f.haptics.isEmpty())
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    } }

    @Test fun aSeparatelyFocusableNativeSwitchKeepsVisibleKeyboardInkWithoutReintroducingGrayPressIndication() = onMotionTestThread { runTest {
        val failures = mutableListOf<String>()
        for (scale in listOf(1f, 0f)) {
            var active by mutableStateOf(true); var calls = 0; var accent = 0
            var mode: InputModeManager? = null
            ManagerPressRenderFixture(testScheduler, durationScale = scale) {
                val manager = LocalInputModeManager.current; val ink = LocalSoftVoWiFiColors.current.interactive
                SideEffect { mode = manager; accent = ink.toArgb() }
                CompositionLocalProvider(LocalAppRouteActive provides active) {
                    ManagerTogglePreferenceItem(managerTogglePreference(AppearancePreferenceKey.HAPTICS_ENABLED,
                        "合成开关", "合成说明", CallPhoneIcons.Phone, false), { calls++ })
                }
            }.use { f ->
                val e = f.environment; val keyboard = Canvas()
                fun consumeNow() { repeat(4) { e.advanceFrameBy(0) } }
                consumeNow()
                val controls = f.actions(); assertEquals(2, controls.size, "前提：原行及库Switch均有真实动作")
                val native = controls.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
                val bounds = native.boundsInRoot
                assertTrue(bounds.width * bounds.height < controls.maxOf { it.boundsInRoot.width * it.boundsInRoot.height } / 2)
                val region = Rect(bounds.center.x - 2f, bounds.top - 1f, bounds.center.x + 2f, bounds.top + 4f)
                fun ink() = s3SmallPatch(e, testScheduler, region).pixels.count { s3RgbDistance(it, accent) <= 8 }
                assertEquals(0, ink(), "前提：未选中Switch外沿原无强调色")
                var reached = false
                repeat(8) {
                    if (!reached) {
                        for (down in listOf(true, false)) e.scene.sendKeyEvent(KeyEvent(Key.Tab,
                            if (down) KeyEventType.KeyDown else KeyEventType.KeyUp, nativeEvent = AwtKeyEvent(keyboard,
                                if (down) AwtKeyEvent.KEY_PRESSED else AwtKeyEvent.KEY_RELEASED,
                                testScheduler.currentTime, 0, AwtKeyEvent.VK_TAB, AwtKeyEvent.CHAR_UNDEFINED)))
                        consumeNow()
                        reached = f.actions().any { it.id == native.id && it.config.getOrNull(SemanticsProperties.Focused) == true }
                    }
                }
                assertTrue(reached, "前提：真实Tab须到达库原Switch，不能把外层行焦点冒充")
                assertEquals(InputMode.Keyboard, assertNotNull(mode).inputMode)
                assertEquals(bounds, f.actions().single { it.id == native.id }.boundsInRoot)
                if (ink() < 2) failures += "Native Switch scale=$scale：原源键盘聚焦缺2dp强调色环"
                assertTrue(assertNotNull(mode).requestInputMode(InputMode.Touch)); consumeNow()
                if (ink() != 0) failures += "Native Switch scale=$scale：Touch未撤环"
                active = false; consumeNow(); assertEquals(0, calls); assertTrue(f.haptics.isEmpty())
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    } }
}
