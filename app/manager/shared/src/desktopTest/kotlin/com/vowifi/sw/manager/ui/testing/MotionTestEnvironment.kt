package com.vowifi.sw.manager.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import java.lang.reflect.InvocationTargetException
import javax.swing.SwingUtilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * 真实 Compose 场景的指针事件、协程延迟与渲染帧共用同一虚拟时钟。
 * [durationScale] 经场景协程的 MotionDurationScale 生效，不手动赋动画终值。
 * 指针注入只处理当前时刻任务，不推进时间或调用语义动作。
 * 场景及状态写入须在 [onMotionTestThread] 内执行，与 Compose Desktop 的全局快照通知串行。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
internal class MotionTestEnvironment(
    private val scheduler: TestCoroutineScheduler,
    width: Int = 240,
    height: Int = 240,
    val durationScale: Float = 1f,
    dispatcher: TestDispatcher = UnconfinedTestDispatcher(scheduler),
    motionScale: MotionDurationScale? = null,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private var inputMode: InputModeManager? = null
    val scene = ImageComposeScene(
        width = width,
        height = height,
        coroutineContext = dispatcher + (motionScale ?: object : MotionDurationScale {
            override val scaleFactor = durationScale
        }),
        content = {
            val platformMode = LocalInputModeManager.current
            SideEffect { inputMode = platformMode }
            content()
        },
    )

    fun advanceFrameBy(milliseconds: Long = 16) {
        scheduler.advanceTimeBy(milliseconds)
        scheduler.runCurrent()
        scene.render(scheduler.currentTime * 1_000_000L).close()
        scheduler.runCurrent()
    }

    fun press(position: Offset, type: PointerType = PointerType.Touch) {
        // ImageComposeScene has no Android onTouchModeChanged callback. Model that platform
        // device switch before delivering the real pointer; do not synthesize focus/press events.
        if (type == PointerType.Touch) inputMode?.requestInputMode(InputMode.Touch)
        scene.sendPointerEvent(PointerEventType.Press, position, timeMillis = scheduler.currentTime, type = type)
        scheduler.runCurrent()
    }

    fun move(position: Offset, type: PointerType = PointerType.Touch) {
        scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = scheduler.currentTime, type = type)
        scheduler.runCurrent()
    }

    fun release(position: Offset, type: PointerType = PointerType.Touch) {
        scene.sendPointerEvent(PointerEventType.Release, position, timeMillis = scheduler.currentTime, type = type)
        scheduler.runCurrent()
    }

    /** 滚轮独立使用 Scroll 事件；触摸滚动接管须用 press/move/release 交给容器判定。 */
    fun scroll(position: Offset, delta: Offset) {
        scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = delta,
            timeMillis = scheduler.currentTime, type = PointerType.Mouse)
        scheduler.runCurrent()
    }

    override fun close() {
        scene.close()
        scheduler.runCurrent()
    }
}

/** GlobalSnapshotManager 使用 AWT 主线程；避免通知与测试线程的 render 竞争。 */
internal fun onMotionTestThread(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        try { SwingUtilities.invokeAndWait(block) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
}
