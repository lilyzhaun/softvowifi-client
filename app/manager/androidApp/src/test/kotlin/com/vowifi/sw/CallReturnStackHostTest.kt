package com.vowifi.sw

import android.Manifest
import android.content.Intent
import android.os.Looper
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.SoftVoWiFiRuntime
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** 真实返回handler的宿主交接合同；不证明WM/OEM已播放动画或真实任务栈已被模拟。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, MessageSourceShadow::class, CallReturnMotionActivityShadow::class])
class CallReturnStackHostTest {
    @Before fun notificationsAreAlreadyAllowed() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun warmReturnPreparesOriginalMainAndOnlyFinishesCallWithoutRelaunchingEitherHost() = verifyWarmReturn()

    @Test @Config(sdk = [33])
    fun api33WarmReturnPreparesOriginalMainWithoutRelaunchingEitherHost() = verifyWarmReturn()

    private fun verifyWarmReturn() = withRuntime { fixture, runtime ->
        Robolectric.buildActivity(MainActivity::class.java).use { main ->
            main.create().start().resume(); idle()
            val originalMain = main.get()
            val open = requireNotNull(shadowOf(originalMain).nextStartedActivityForResult).intent
            assertEquals(CallActivity::class.java.name, open.component?.className)
            val key = requireNotNull(open.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
            assertEquals(runtime.notifications.keyForCurrent(), key)
            assertNotNull(runtime.notifications.resolveKey(key))
            assertNull(shadowOf(originalMain).nextStartedActivityForResult)
            main.pause().stop()
            Robolectric.buildActivity(CallActivity::class.java, open).use { call ->
                call.create().start().resume(); idle()
                val activity = call.get()
                val counter = Shadow.extract<CallReturnMotionActivityShadow>(activity)
                assertTrue("前提：默认taskId有效，0也有效；不伪造taskId", originalMain.taskId >= 0)
                assertEquals("前提：两个真实controller的taskId相同", originalMain.taskId, activity.taskId)
                assertFalse(activity.isFinishing)
                assertTrue("前提：原通知key已授权Call宿主", shadowOf(activity).showWhenLocked)
                assertEquals(CallPhase.Ringing, (runtime.calls.state.value as CallUiState.Ready).phase)
                assertEquals(0, counter.finishCalls); assertTrue(fixture.commands.isEmpty())

                activity.onBackPressedDispatcher.onBackPressed(); idle()

                assertTrue(activity.isFinishing)
                assertEquals(1, counter.finishCalls)
                assertFalse(shadowOf(activity).showWhenLocked); assertFalse(shadowOf(activity).turnScreenOn)
                assertNull("同任务已有Main时不得再startActivity/CLEAR_TOP提前清除Call",
                    shadowOf(activity).nextStartedActivityForResult)
                assertEquals(key, originalMain.intent.getStringExtra(CallActivity.EXTRA_MINIMIZED))
                assertEquals("calls", originalMain.intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION))
                assertFalse(originalMain.isFinishing); assertFalse(originalMain.isDestroyed)
                activity.onBackPressedDispatcher.onBackPressed(); idle()
                assertEquals("重复返回只能finish一次", 1, counter.finishCalls)
                assertNull(shadowOf(activity).nextStartedActivityForResult)
                assertTrue(fixture.commands.isEmpty())
                call.pause().stop().destroy()
            }
            main.restart().start().resume(); idle()
            assertNull("恢复原Main不得二开Call", shadowOf(originalMain).nextStartedActivityForResult)
            fixture.revision++; fixture.source.refresh(); idle()
            assertNull("同一授权通话刷新不得二开Call", shadowOf(originalMain).nextStartedActivityForResult)
            assertEquals(key, originalMain.intent.getStringExtra(CallActivity.EXTRA_MINIMIZED))
            assertEquals(key, runtime.notifications.keyForCurrent())
            assertEquals(CallPhase.Ringing, (runtime.calls.state.value as CallUiState.Ready).phase)
            assertTrue("最小化不能answer/hangup/cancel/dial", fixture.commands.isEmpty())
            main.pause().stop().destroy()
        }
    }

    @Test fun coldReturnCreatesMainWithOriginalKeyAndCallsDestinationAfterOriginalHostIsDestroyed() = withRuntime { fixture, runtime ->
        val open = Robolectric.buildActivity(MainActivity::class.java).use { main ->
            main.create().start().resume(); idle()
            val launch = requireNotNull(shadowOf(main.get()).nextStartedActivityForResult).intent
            assertEquals(CallActivity::class.java.name, launch.component?.className)
            assertNull(shadowOf(main.get()).nextStartedActivityForResult)
            main.pause().stop().destroy()
            assertTrue("前提：通知key取得后原Main已经销毁", main.get().isDestroyed)
            launch
        }
        val key = requireNotNull(open.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
        assertEquals(runtime.notifications.keyForCurrent(), key)
        assertNotNull(runtime.notifications.resolveKey(key))
        val returned = Robolectric.buildActivity(CallActivity::class.java, open).use { call ->
            call.create().start().resume(); idle()
            val activity = call.get()
            val counter = Shadow.extract<CallReturnMotionActivityShadow>(activity)
            assertFalse(activity.isFinishing); assertTrue(shadowOf(activity).showWhenLocked)
            assertEquals(0, counter.finishCalls); assertTrue(fixture.commands.isEmpty())
            activity.onBackPressedDispatcher.onBackPressed(); idle()
            val launch = requireNotNull(shadowOf(activity).nextStartedActivityForResult) {
                "无原Main时必须保留安全创建管理页的兜底，不能只finish"
            }
            assertEquals(MainActivity::class.java.name, launch.intent.component?.className)
            val flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            assertEquals(flags, launch.intent.flags and flags)
            assertEquals(key, launch.intent.getStringExtra(CallActivity.EXTRA_MINIMIZED))
            assertEquals("calls", launch.intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION))
            assertNotNull("冷启动保留真实平台options，而非手造Bundle", launch.options)
            assertTrue(activity.isFinishing); assertEquals(1, counter.finishCalls)
            assertFalse(shadowOf(activity).showWhenLocked); assertFalse(shadowOf(activity).turnScreenOn)
            activity.onBackPressedDispatcher.onBackPressed(); idle()
            assertEquals(1, counter.finishCalls); assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertTrue(fixture.commands.isEmpty())
            call.pause().stop().destroy()
            launch.intent
        }
        Robolectric.buildActivity(MainActivity::class.java, returned).use { main ->
            main.create().start().resume(); idle()
            assertEquals(key, main.get().intent.getStringExtra(CallActivity.EXTRA_MINIMIZED))
            assertEquals("calls", main.get().intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION))
            assertNull("冷返回Main也不得重新打开同一Call", shadowOf(main.get()).nextStartedActivityForResult)
            assertEquals(CallPhase.Ringing, (runtime.calls.state.value as CallUiState.Ready).phase)
            assertEquals(key, runtime.notifications.keyForCurrent()); assertTrue(fixture.commands.isEmpty())
            main.pause().stop().destroy()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun withRuntime(block: (CallPlatformFixture, SoftVoWiFiRuntime) -> Unit) {
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        try {
            val application = RuntimeEnvironment.getApplication()
            check(application is SoftVoWiFiApplication)
            val runtime = application.runtime()
            try { block(fixture, runtime) } finally {
                try { runtime.notifications.stop() } finally { runtime.calls.lifecycle.close() }
            }
        } finally {
            RuntimeApplicationShadow.callFixture = null
            fixture.close()
        }
    }
}
