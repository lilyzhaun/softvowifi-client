package com.vowifi.sw

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.IncomingCallAction
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, MessageSourceShadow::class])
class CallActivityReliabilityHostTest {
    @Before fun notificationsAreAlreadyAllowed() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun stableIncomingCallDoesNotPingPongAcrossRepeatedIntentsAndResumes() {
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        try {
            Robolectric.buildActivity(MainActivity::class.java).use { main ->
                main.create().start().resume()
                idle()
                val open = requireNotNull(shadowOf(main.get()).nextStartedActivity)
                assertEquals(CallActivity::class.java.name, open.component?.className)
                val key = open.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY)
                assertEquals(runtime.notifications.keyForCurrent(), key)
                assertNull(shadowOf(main.get()).nextStartedActivity)
                main.pause().stop()
                Robolectric.buildActivity(CallActivity::class.java, open).use { call ->
                    call.create().start().resume()
                    idle()
                    repeat(3) {
                        call.newIntent(Intent(open))
                        idle()
                        assertFalse(call.get().isFinishing)
                        assertTrue(shadowOf(call.get()).showWhenLocked)
                        assertNull(shadowOf(call.get()).nextStartedActivity)
                        call.pause().stop()
                        main.restart().start().resume()
                        fixture.revision++
                        fixture.source.refresh()
                        idle()
                        assertNull(shadowOf(main.get()).nextStartedActivity)
                        main.pause().stop()
                        call.restart().start().resume()
                        idle()
                        assertEquals(key, runtime.notifications.keyForCurrent())
                    }
                    call.pause().stop().destroy()
                }
                main.destroy()
            }
            assertTrue(fixture.commands.isEmpty())
        } finally {
            runtime.notifications.stop()
            runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null
            fixture.close()
        }
    }

    @Test fun explicitMinimizationSurvivesWarmResumeAndSavedInstanceRecreation() {
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        try {
            Robolectric.buildActivity(MainActivity::class.java).use { main ->
                main.create().start().resume()
                idle()
                val open = requireNotNull(shadowOf(main.get()).nextStartedActivity)
                main.pause().stop()
                val minimized = Robolectric.buildActivity(CallActivity::class.java, open).use { call ->
                    call.create().start().resume()
                    idle()
                    call.get().onBackPressedDispatcher.onBackPressed()
                    idle()
                    assertTrue(call.get().isFinishing)
                    assertFalse(shadowOf(call.get()).showWhenLocked)
                    // 已有同任务Main直接接收最小化key；不靠CLEAR_TOP重启它才继续CLOSE。
                    assertNull(shadowOf(call.get()).nextStartedActivity)
                    val launch = Intent(main.get().intent)
                    assertEquals(MainActivity::class.java.name, launch.component?.className)
                    assertEquals(runtime.notifications.keyForCurrent(), launch.getStringExtra(CallActivity.EXTRA_MINIMIZED))
                    assertNull(shadowOf(call.get()).nextStartedActivity)
                    call.pause().stop().destroy()
                    launch
                }
                main.restart().start().resume()
                idle()
                assertNull(shadowOf(main.get()).nextStartedActivity)
                repeat(3) {
                    main.pause().stop().restart().start().resume()
                    fixture.revision++
                    fixture.source.refresh()
                    idle()
                    assertNull(shadowOf(main.get()).nextStartedActivity)
                }
                val saved = Bundle()
                main.pause().saveInstanceState(saved).stop().destroy()
                Robolectric.buildActivity(MainActivity::class.java, Intent(minimized)).use { recreated ->
                    recreated.create(saved).start().resume()
                    idle()
                    assertNull(shadowOf(recreated.get()).nextStartedActivity)
                    assertFalse(shadowOf(recreated.get()).showWhenLocked)
                    fixture.token = "next-incoming-call"
                    fixture.epoch = "next-incoming-epoch"
                    fixture.revision++
                    fixture.source.refresh()
                    idle()
                    val next = requireNotNull(shadowOf(recreated.get()).nextStartedActivity)
                    assertEquals(CallActivity::class.java.name, next.component?.className)
                    assertNotEquals(minimized.getStringExtra(CallActivity.EXTRA_MINIMIZED),
                        next.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
                    assertNull(shadowOf(recreated.get()).nextStartedActivity)
                    recreated.pause().stop().destroy()
                }
            }
            assertTrue(fixture.commands.isEmpty())
        } finally {
            runtime.notifications.stop()
            runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null
            fixture.close()
        }
    }

    @Test(timeout = 30_000)
    @LooperMode(LooperMode.Mode.PAUSED)
    fun actualTerminalEventsUseFailureAwareDwellAndThePlatformRecommendedTimeout() = withDwellRecomposer {
        fun idle() = idleDwell()
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        val accessibility = requireNotNull(context.getSystemService(AccessibilityManager::class.java))
        val manager = shadowOf(accessibility)
        val animatorScale = android.provider.Settings.Global.getString(context.contentResolver, "animator_duration_scale")
        val flags = AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS or
            AccessibilityManager.FLAG_CONTENT_CONTROLS
        try {
            // 默认时长及无障碍延长分别通过真实平台推荐API；不写生产timer或手填UI状态。
            val cases = listOf(Triple("remote", 1200, 0), Triple("hangup", 1200, 0),
                Triple("failed", 4000, 0), Triple("timeout", 4000, 0), Triple("rejected", 4000, 0),
                Triple("remote", 1200, 2500), Triple("failed", 4000, 6000), Triple("failed", 4000, 0))
            cases.forEachIndexed { index, (reason, originalMillis, recommendedMillis) ->
                if (index == cases.lastIndex) android.provider.Settings.Global.putString(context.contentResolver, "animator_duration_scale", "0")
                manager.setInteractiveUiTimeout(recommendedMillis)
                manager.setNonInteractiveUiTimeout(recommendedMillis)
                manager.setTouchExplorationEnabled(false)
                val expectedMillis = accessibility.getRecommendedTimeoutMillis(originalMillis, flags)
                assertEquals(maxOf(originalMillis, recommendedMillis), expectedMillis)
                fixture.stage = "RINGING"
                fixture.token = "s6-dwell-call-$index"
                fixture.epoch = "s6-dwell-epoch-$index"
                fixture.revision++
                fixture.source.refresh()
                idle()
                val key = requireNotNull(runtime.notifications.keyForCurrent())
                val intent = Intent(context, CallActivity::class.java)
                    .putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, key)
                Robolectric.buildActivity(CallActivity::class.java, intent).use { call ->
                    call.create().start().resume().visible()
                    idle()
                    assertNotNull(runtime.notifications.resolveKey(key))
                    assertFalse(call.get().isFinishing)
                    val eventAt = emitDwellTerminal(fixture, reason)
                    idle()
                    val ended = fixture.source.state.value as? CallUiState.Ready
                    assertNotNull("前提：真实公开wire已驱动EngineCallSource", ended)
                    assertEquals("合法终态须经原解析器采纳：case=$index/$reason", CallPhase.Idle, ended!!.phase)
                    if (originalMillis == 1200) assertNull(ended.operationMessage)
                    else assertNotNull("typed失败须保安全原因", ended.terminal?.message)
                    endAction(call.get()) // 确认真实结束按钮已组合/布局，之后才开始停留断言。
                    val terminalAt = SystemClock.uptimeMillis()
                    assertTrue("事件到真实结束首帧最多一个16ms帧，不能任意延后阅读起点", terminalAt - eventAt in 0L..16L)
                    if (originalMillis == 4000) {
                        advanceDwellTo(terminalAt, 1200)
                        assertFalse("真实typed失败不能沿正常1200ms退出，含零动画", call.get().isFinishing)
                        advanceDwellTo(terminalAt, expectedMillis.toLong() - 1)
                    } else advanceDwellTo(terminalAt, expectedMillis.toLong() - 1)
                    assertFalse("推荐停留时间之前仍应在真实Activity", call.get().isFinishing)
                    advanceDwellClock(1)
                    assertTrue("真实终态须在推荐时长后离开：case=$index/$reason，已用=${SystemClock.uptimeMillis() - terminalAt}ms，期望=${expectedMillis}ms", call.get().isFinishing)
                    val launched = requireNotNull(shadowOf(call.get()).nextStartedActivity)
                    assertEquals(MainActivity::class.java.name, launched.component?.className)
                    assertEquals("calls", launched.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION))
                    assertNull(shadowOf(call.get()).nextStartedActivity)
                    call.pause().stop().destroy()
                    advanceDwellClock(4500)
                    assertNull("旧停留任务不能重复发管理页", shadowOf(call.get()).nextStartedActivity)
                }
            }
            assertTrue(fixture.commands.isEmpty())
        } finally {
            android.provider.Settings.Global.putString(context.contentResolver, "animator_duration_scale", animatorScale)
            manager.setInteractiveUiTimeout(0)
            manager.setNonInteractiveUiTimeout(0)
            runtime.notifications.stop()
            runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null
            fixture.close()
        }
    }

    @Test(timeout = 30_000)
    @LooperMode(LooperMode.Mode.PAUSED)
    fun realNewIntentCancelsOldEndDwellAndTheExistingReturnButtonLeavesOnce() = withDwellRecomposer {
        fun idle() = idleDwell()
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        val manager = shadowOf(requireNotNull(context.getSystemService(AccessibilityManager::class.java)))
        manager.setInteractiveUiTimeout(0)
        manager.setNonInteractiveUiTimeout(0)
        manager.setTouchExplorationEnabled(false)
        try {
            val oldKey = requireNotNull(runtime.notifications.keyForCurrent())
            val original = Intent(context, CallActivity::class.java)
                .putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, oldKey)
            Robolectric.buildActivity(CallActivity::class.java, original).use { call ->
                call.create().start().resume().visible()
                idle()
                assertNotNull(runtime.notifications.resolveKey(oldKey))
                assertFalse(call.get().isFinishing)
                emitDwellTerminal(fixture, "remote")
                idle()
                assertEquals(CallPhase.Idle, (fixture.source.state.value as CallUiState.Ready).phase)
                endAction(call.get())
                advanceDwellClock(600)
                assertFalse(call.get().isFinishing)
                fixture.stage = "RINGING"
                fixture.token = "s6-replacement-call"
                fixture.epoch = "s6-replacement-epoch"
                fixture.revision++
                fixture.source.refresh()
                val nextKey = requireNotNull(runtime.notifications.keyForCurrent())
                assertNotEquals(oldKey, nextKey)
                // 不在source发布与真实onNewIntent之间插入人为等待；真实key来自原coordinator。
                call.newIntent(Intent(original)
                    .putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY, nextKey))
                idle()
                assertNotNull(runtime.notifications.resolveKey(nextKey))
                assertEquals(CallPhase.Ringing, (fixture.source.state.value as CallUiState.Ready).phase)
                advanceDwellClock(6500)
                assertFalse("新来电不能被旧1200/4000ms任务带走", call.get().isFinishing)
                assertNull(shadowOf(call.get()).nextStartedActivity)
                emitDwellTerminal(fixture, "failed")
                idle()
                assertEquals(CallPhase.Idle, (fixture.source.state.value as CallUiState.Ready).phase)
                advanceDwellClock(500)
                assertFalse(call.get().isFinishing)
                val (view, node) = endAction(call.get())
                val point = node.boundsInRoot.center
                val down = SystemClock.uptimeMillis()
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point.x, point.y, 0).useEvent {
                    assertTrue("前提：真实结束按钮接收指针DOWN", view.dispatchTouchEvent(it))
                }
                MotionEvent.obtain(down, down + 16, MotionEvent.ACTION_UP, point.x, point.y, 0).useEvent {
                    assertTrue("前提：真实结束按钮接收指针UP", view.dispatchTouchEvent(it))
                }
                idle()
                assertTrue("现有返回按钮应早于结束停留立即离开", call.get().isFinishing)
                assertEquals(MainActivity::class.java.name,
                    requireNotNull(shadowOf(call.get()).nextStartedActivity).component?.className)
                assertNull(shadowOf(call.get()).nextStartedActivity)
                call.pause().stop().destroy() // 按实际finish生命周期销毁，避免host人为保留已退出页面。
                advanceDwellClock(6500)
                assertNull("按钮离开后不能再由旧timer二次startActivity", shadowOf(call.get()).nextStartedActivity)
            }
            assertTrue(fixture.commands.isEmpty())
        } finally {
            runtime.notifications.stop()
            runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null
            fixture.close()
        }
    }

    @Test(timeout = 30_000)
    @LooperMode(LooperMode.Mode.PAUSED)
    fun terminalDwellAcceptsFreshIssuedCallWithoutNewIntentOrReplayingOldAnswer() = withDwellRecomposer {
        fun idle() = idleDwell()
        val fixture = CallPlatformFixture("IN_CALL")
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        try {
            val key = requireNotNull(runtime.notifications.keyForCurrent())
            val original = runtime.notifications.openIntent(key, answer = true)
            Robolectric.buildActivity(CallActivity::class.java, original).use { call ->
                call.create().start().resume().visible(); idle()
                assertNotNull(runtime.notifications.resolveKey(key))
                assertFalse(call.get().isFinishing)
                emitDwellTerminal(fixture, "remote"); idle(); endAction(call.get())
                advanceDwellClock(600)
                fixture.stage = "RINGING"
                fixture.token = "s6-dwell-arriving-call"
                fixture.epoch = "s6-dwell-arriving-epoch"
                fixture.revision++
                fixture.source.refresh(); idle() // 不调用newIntent；只能由真实fresh source驱动。
                repeat(2) { dwellFrames.sendFrame(SystemClock.uptimeMillis() * 1_000_000L); idle() }
                assertFalse(call.get().isFinishing)
                val nextKey = requireNotNull(runtime.notifications.keyForCurrent())
                assertNotEquals(key, nextKey)
                assertEquals(nextKey, call.get().intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
                assertNotNull(runtime.notifications.resolveKey(nextKey))
                assertNotEquals(IncomingCallAction.ANSWER.actionName, call.get().intent.action)
                advanceDwellClock(6500)
                assertFalse("新call不能被旧阅读deadline带走", call.get().isFinishing)
                assertEquals(CallPhase.Ringing, (fixture.source.state.value as CallUiState.Ready).phase)
                assertTrue(shadowOf(call.get()).showWhenLocked)
                assertNull(shadowOf(call.get()).nextStartedActivity)
                assertTrue("旧ANSWER不能重放为新call控制RPC", fixture.commands.isEmpty())
                call.pause().stop().destroy()
            }
        } finally {
            runtime.notifications.stop(); runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null; fixture.close()
        }
    }

    @Test(timeout = 30_000)
    @LooperMode(LooperMode.Mode.PAUSED)
    fun terminalRotationRetainsOnlyFrameworkAuthorizationAndItsOriginalDeadline() = withDwellRecomposer {
        fun idle() = idleDwell()
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        try {
            val key = requireNotNull(runtime.notifications.keyForCurrent())
            val original = runtime.notifications.openIntent(key)
            Robolectric.buildActivity(CallActivity::class.java, original).use { call ->
                call.create().start().resume().visible(); idle()
                assertNotNull(runtime.notifications.resolveKey(key))
                assertEquals("前提：真实源仍为Ringing，不能以终态创建代替旧身份首帧", CallPhase.Ringing,
                    (fixture.source.state.value as CallUiState.Ready).phase)
                assertEquals("前提：真实Ringing首帧已布局显示synthetic peer", 1, s7Peer(call.get(), awaitInitialHost = true))
                val ringingFirstFrameAt = SystemClock.uptimeMillis()
                val eventAt = emitDwellTerminal(fixture, "failed")
                assertTrue("真实wire终态不能早于已观测的Ringing身份首帧", eventAt >= ringingFirstFrameAt)
                idle(); endAction(call.get())
                assertEquals("前提：旧终态真实UI仍显示synthetic peer", 1, s7Peer(call.get()))
                val terminalReadingStartedAt = SystemClock.uptimeMillis()
                assertTrue("结束首帧起点不得晚于一个16ms帧", terminalReadingStartedAt - eventAt in 0L..16L)
                advanceDwellClock(500)
                val oldInstance = call.get()
                val nextConfiguration = android.content.res.Configuration(call.get().resources.configuration).apply {
                    orientation = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                        android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
                // 真实配置重建保框架nonConfig；4.16.1 recreate强行在根附着前派windowFocus会NPE。
                call.configurationChange(nextConfiguration).visible(); idle()
                assertNotSame(oldInstance, call.get())
                assertFalse("同进程旋转应延续原框架授权", call.get().isFinishing)
                endAction(call.get())
                assertTrue("旋转后的真实window根必须已附着", call.get().window.decorView.isAttachedToWindow)
                assertEquals("S7：真正配置重建后的终态身份必须保留，auth/deadline通过不等于身份通过", 1, s7Peer(call.get()))
                assertNull("终态不能把旧opaque key作为新建许可", runtime.notifications.resolveKey(key))
                Robolectric.buildActivity(CallActivity::class.java, Intent(original)).use { untrusted ->
                    untrusted.create().start().resume(); idle()
                    assertTrue("无框架保留授权的新建Activity必须拒绝过期key", untrusted.get().isFinishing)
                    assertNull(shadowOf(untrusted.get()).nextStartedActivity)
                    untrusted.pause().stop().destroy(); idle()
                }
                val remaining = 4000L - (SystemClock.uptimeMillis() - terminalReadingStartedAt)
                assertTrue("重建不能消耗完原阅读窗口", remaining > 0)
                advanceDwellTo(terminalReadingStartedAt, 3999)
                assertFalse(call.get().isFinishing)
                advanceDwellClock(1)
                assertTrue("旋转不能重置完整4000ms阅读时长", call.get().isFinishing)
                assertEquals(MainActivity::class.java.name, requireNotNull(shadowOf(call.get()).nextStartedActivity).component?.className)
                assertNull(shadowOf(call.get()).nextStartedActivity)
                call.pause().stop().destroy()
            }
        } finally {
            runtime.notifications.stop(); runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null; fixture.close()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    private fun s7Peer(activity: CallActivity, awaitInitialHost: Boolean = false): Int {
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        // Only before the wire terminal: PAUSED Robolectric still needs real frames to attach its host.
        // The terminal/recreation observations below never advance their existing reading deadline.
        if (awaitInitialHost) repeat(10) {
            if (views(activity.window.decorView).none { it is ViewRootForTest }) advanceDwellClock(16)
        }
        val at = SystemClock.uptimeMillis()
        idleDwell()
        val roots = views(activity.window.decorView).filterIsInstance<ViewRootForTest>()
        assertTrue("前提：synthetic peer必须来自真实ViewRoot，不能借action父链猜merged树", roots.isNotEmpty())
        roots.forEach { it.measureAndLayoutForTest() }
        idleDwell()
        assertEquals("peer首帧只消费同刻frame/measure，不增加终态阅读deadline", at, SystemClock.uptimeMillis())
        return roots.sumOf { root ->
            tree(root.semanticsOwner.unmergedRootSemanticsNode).count { node ->
                val b = node.boundsInRoot
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "+1 202 555 0123" } == true &&
                    b.width > 0 && b.height > 0 && b.left >= 0 && b.top >= 0 &&
                    b.right <= root.view.width && b.bottom <= root.view.height
            }
        }
    }

    private fun emitDwellTerminal(fixture: CallPlatformFixture, reason: String): Long {
        // 现fixture公开的实际MutableSharedFlow；仅强转已知测试传输，不反射业务授权。
        val events = fixture.primary.events as MutableSharedFlow<EngineProtocolFrame.Event>
        assertTrue("前提：真实EngineCallSource已订阅传输", events.subscriptionCount.value > 0)
        fixture.stage = "IDLE"
        fixture.revision++
        val terminalAt = SystemClock.uptimeMillis()
        runBlocking {
            // timeout/rejected来自入向ENDED；callEnded原命名空间不接受这两种reason。
            val incoming = reason == "timeout" || reason == "rejected"
            events.emit(EngineProtocolFrame.Event(if (incoming) "incomingCall" else "callEnded", buildJsonObject {
                if (incoming) put("state", "ENDED")
                put("callId", fixture.token)
                put("epoch", fixture.epoch)
                put("revision", fixture.revision)
                put("reason", reason)
                put("durationSec", 0)
            }))
        }
        return terminalAt
    }

    private val dwellFrames = BroadcastFrameClock()

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun withDwellRecomposer(block: () -> Unit) {
        // Main Handler的Delay与PAUSED Looper共用时钟；公开FrameClock交付同一uptime的真实重组帧。
        // 不替换业务timer，View生命周期及factory恢复仍由Compose保留。
        val paused = org.robolectric.shadows.ShadowChoreographer.isPaused()
        // 4.16.1的默认scheduleVsync会主动advanceBy(15ms)；阅读截止只能由本测试显式推进。
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        try {
            WindowRecomposerPolicy.withFactory(WindowRecomposerFactory { view ->
                view.createLifecycleAwareWindowRecomposer(coroutineContext = Dispatchers.Main.immediate + dwellFrames)
            }, block)
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(paused)
        }
    }

    private fun idleDwell() {
        idle()
        repeat(2) {
            // native vsync暂停后，明确提交真实Snapshot通知，不依赖另一个隐式Choreographer帧。
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            idle()
            dwellFrames.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
            idle()
        }
    }

    private fun advanceDwellClock(milliseconds: Long) {
        require(milliseconds >= 0)
        // 先处理销毁后的平台root清理，再采时；清理泵不属于本次指定阅读时长。
        idleDwell()
        val before = SystemClock.uptimeMillis()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
        idleDwell()
        assertEquals("协程Delay与Android Looper必须只共同推进指定时长", before + milliseconds, SystemClock.uptimeMillis())
    }

    private fun advanceDwellTo(terminalAt: Long, offset: Long) {
        idleDwell()
        val remaining = terminalAt + offset - SystemClock.uptimeMillis()
        assertTrue("真实host就绪不能越过阅读边界", remaining >= 0)
        advanceDwellClock(remaining)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    private fun endAction(activity: CallActivity): Pair<View, SemanticsNode> {
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        fun hits(): List<Pair<View, SemanticsNode>> = views(activity.window.decorView).filterIsInstance<ViewRootForTest>().flatMap { root ->
            val nodes = tree(root.semanticsOwner.unmergedRootSemanticsNode)
            val parents = nodes.flatMap { parent -> parent.children.map { it.id to parent } }.toMap()
            nodes.mapNotNull { node ->
                if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("返回通话记录") != true &&
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "返回通话记录" } != true) return@mapNotNull null
                var action = node
                while (!action.config.contains(SemanticsActions.OnClick) && parents[action.id] != null) action = parents.getValue(action.id)
                val b = action.boundsInRoot
                action.takeIf { it.config.contains(SemanticsActions.OnClick) && b.width > 0 && b.height > 0 &&
                    b.left >= 0 && b.top >= 0 && b.right <= root.view.width && b.bottom <= root.view.height &&
                    b.width < root.view.width * .9f && b.height < root.view.height * .5f }
            }.distinctBy { it.id }.map { root.view to it }
        }
        fun frame() {
            repeat(2) { dwellFrames.sendFrame(SystemClock.uptimeMillis() * 1_000_000L); idle() }
            views(activity.window.decorView).filterIsInstance<ViewRootForTest>().forEach { root ->
                root.measureAndLayoutForTest()
            }
        }
        frame()
        var found = hits()
        repeat(10) {
            if (found.isEmpty()) { advanceDwellClock(16); frame(); found = hits() }
        }
        val hits = found
        assertEquals("前提：实际CallEndContent只有一个原返回按钮", 1, hits.size)
        val (view, node) = hits.single()
        val b = node.boundsInRoot
        assertTrue("前提：原Compose按钮完整布局可点击", b.width > 0 && b.height > 0 &&
            b.left >= 0 && b.top >= 0 && b.right <= view.width && b.bottom <= view.height)
        return view to node
    }

    private inline fun MotionEvent.useEvent(block: (MotionEvent) -> Unit) {
        try { block(this) } finally { recycle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
