package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.scene.ComposeScenePointer
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §4.6：第二根手指介入同一控件后，不得由剩余指针抬起提交点击。 */
// The multi-pointer scene overload is version-coupled; opt in only in this pinned desktop test.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ManagerMultiPointerPressTest {
    @Test
    fun iconButtonCancelsWhenASecondTouchEntersItsTarget() = verifyTwoTouches { onClick ->
        ManagerIconAction(ManagerPressTestIcon, "测试按钮", ManagerAction.ContextAction, onClick)
    }

    @Test
    fun phoneButtonCancelsWhenASecondTouchEntersItsTarget() = verifyTwoTouches { onClick ->
        PhoneButton("测试按钮", onClick, material = false)
    }

    private fun verifyTwoTouches(control: @Composable (() -> Unit) -> Unit) = onMotionTestThread {
        runTest {
            var clicks = 0
            var observedPointers = 0
            ManagerPressRenderFixture(testScheduler) {
                Box(Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            observedPointers = maxOf(observedPointers, event.changes.count { it.pressed })
                        }
                    }
                }) { control { clicks++ } }
            }.use { fixture ->
                val center = fixture.actions().single().boundsInRoot.center
                fun pointer(id: Long, pressed: Boolean) = ComposeScenePointer(
                    id = PointerId(id), position = center + Offset(if (id == 2L) 6f else 0f, 0f),
                    pressed = pressed, type = PointerType.Touch,
                )
                fun event(type: PointerEventType, vararg pointers: ComposeScenePointer) {
                    fixture.environment.scene.sendPointerEvent(type, pointers.toList(), timeMillis = testScheduler.currentTime)
                    testScheduler.runCurrent()
                    fixture.frames()
                }
                event(PointerEventType.Press, pointer(1, true))
                event(PointerEventType.Release, pointer(1, false))
                assertEquals(1, clicks, "多指注入API必须先通过真实单指点击对照")
                assertEquals(1, fixture.haptics.size)
                clicks = 0
                fixture.haptics.clear()

                event(PointerEventType.Press, pointer(1, true))
                event(PointerEventType.Press, pointer(1, true), pointer(2, true))
                event(PointerEventType.Release, pointer(1, false), pointer(2, true))
                event(PointerEventType.Release, pointer(2, false))
                assertEquals(2, observedPointers, "真实指针链必须收到两根同时按下的手指")
                assertEquals(0, clicks, "多指取消后抬起不得提交原点击")
                assertTrue(fixture.haptics.isEmpty())

                event(PointerEventType.Press, pointer(3, true))
                event(PointerEventType.Release, pointer(3, false))
                assertEquals(1, clicks, "取消后的新单指点击仍可用")
                assertEquals(1, fixture.haptics.size)
            }
        }
    }
}
