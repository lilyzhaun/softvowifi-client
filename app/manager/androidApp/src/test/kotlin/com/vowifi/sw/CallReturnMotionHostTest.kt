package com.vowifi.sw

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.animation.AnimationUtils
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity

/** 实际startActivity options接线，不证明Android/OEM采纳或用户已确认收起手感。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, MessageSourceShadow::class, CallReturnMotionActivityShadow::class])
class CallReturnMotionHostTest {
    @Before fun notificationsAreAlreadyAllowed() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun api34ActualReturnLaunchCarriesT5OptionsAndFinishesOnceWithoutCallCommands() = actualReturn()

    @Test @Config(sdk = [33])
    fun api33ActualReturnLaunchCarriesT5OptionsAndFinishesOnceWithoutCallCommands() = actualReturn()

    private fun actualReturn() {
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        val application = RuntimeEnvironment.getApplication()
        check(application is SoftVoWiFiApplication)
        val runtime = application.runtime()
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        try {
            Robolectric.buildActivity(MainActivity::class.java).use { main ->
                main.create().start().resume(); idle()
                // 4.16.1 ShadowInstrumentation真实记录的IntentForResult包含原options，不另造Bundle。
                val initial = requireNotNull(shadowOf(main.get()).nextStartedActivityForResult) { "前提：真实Main须打开授权CallActivity" }
                assertEquals(CallActivity::class.java.name, initial.intent.component?.className)
                val key = requireNotNull(initial.intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
                assertEquals(runtime.notifications.keyForCurrent(), key)
                assertNull(shadowOf(main.get()).nextStartedActivityForResult)
                // 用户回归修正：暖返回只关闭Call。显式启动options仅属于无Main的冷兜底。
                main.pause().stop().destroy()
                Robolectric.buildActivity(CallActivity::class.java, initial.intent).use { call ->
                    call.create().start().resume(); idle()
                    val activity = call.get()
                    val counter = Shadow.extract<CallReturnMotionActivityShadow>(activity)
                    assertFalse(activity.isFinishing)
                    assertTrue("前提：原通知key已真实授权来电宿主", shadowOf(activity).showWhenLocked)
                    assertEquals(CallPhase.Ringing, (runtime.calls.state.value as CallUiState.Ready).phase)
                    assertEquals(0, counter.finishCalls); assertTrue(fixture.commands.isEmpty())
                    activity.onBackPressedDispatcher.onBackPressed(); idle()
                    assertTrue(activity.isFinishing)
                    assertFalse(shadowOf(activity).showWhenLocked); assertFalse(shadowOf(activity).turnScreenOn)
                    val returned = requireNotNull(shadowOf(activity).nextStartedActivityForResult) { "真实返回handler须发出Main启动" }
                    val intent = returned.intent
                    assertEquals(MainActivity::class.java.name, intent.component?.className)
                    val flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    assertEquals(flags, intent.flags and flags)
                    assertEquals("calls", intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION))
                    assertEquals(key, intent.getStringExtra(CallActivity.EXTRA_MINIMIZED))
                    assertEquals(1, counter.finishCalls)
                    activity.onBackPressedDispatcher.onBackPressed(); idle()
                    assertEquals("重复返回不得再次finish", 1, counter.finishCalls)
                    assertNull("重复返回不得再次启动管理页", shadowOf(activity).nextStartedActivityForResult)
                    assertTrue("最小化不能answer/hangup/cancel/dial", fixture.commands.isEmpty())
                    assertEquals(CallPhase.Ringing, (runtime.calls.state.value as CallUiState.Ready).phase)
                    assertEquals(300L, AnimationUtils.loadAnimation(activity, R.anim.s6_call_down).duration)
                    assertEquals(300L, AnimationUtils.loadAnimation(activity, R.anim.s6_call_stay_exit).duration)
                    assertNotNull("T5 return startActivity must carry explicit ActivityOptions; finish-close alone does not bind CLEAR_TOP launch", returned.options)
                    val options = requireNotNull(returned.options)
                    // AOSP ActivityOptions.makeCustomAnimation的官方Bundle资源键。
                    assertEquals("管理页进入保持静止", R.anim.s6_call_stay_exit, options.getInt("android:activity.animEnterRes"))
                    assertEquals("原通话页按T5下收", R.anim.s6_call_down, options.getInt("android:activity.animExitRes"))
                    call.pause().stop().destroy()
                }
            }
            assertTrue(fixture.commands.isEmpty())
        } finally {
            runtime.notifications.stop(); runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null; fixture.close()
        }
    }
}

/** 仅增加finish计数，委托4.16.1原ShadowActivity.finish保持框架mFinished语义；不拦截或伪造options。 */
@Implements(Activity::class)
class CallReturnMotionActivityShadow : ShadowActivity() {
    var finishCalls = 0
        private set

    @Implementation
    public override fun finish() {
        finishCalls++
        super.finish()
    }
}
