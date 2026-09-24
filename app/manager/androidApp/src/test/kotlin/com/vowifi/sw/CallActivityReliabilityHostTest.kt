package com.vowifi.sw

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
                    val launch = requireNotNull(shadowOf(call.get()).nextStartedActivity)
                    assertEquals(MainActivity::class.java.name, launch.component?.className)
                    assertEquals(runtime.notifications.keyForCurrent(), launch.getStringExtra(CallActivity.EXTRA_MINIMIZED))
                    assertNull(shadowOf(call.get()).nextStartedActivity)
                    call.pause().stop().destroy()
                    launch
                }
                main.newIntent(minimized).restart().start().resume()
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

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
