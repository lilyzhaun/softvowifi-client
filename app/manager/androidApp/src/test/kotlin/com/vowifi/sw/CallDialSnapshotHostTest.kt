package com.vowifi.sw

import android.Manifest
import android.content.Intent
import android.os.Looper
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, MessageSourceShadow::class])
class CallDialSnapshotHostTest {
    @Before fun notificationsAreAllowed() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun activityRefreshCannotRetireDialBeforePrimaryRpcCompletes() {
        val fixture = CallPlatformFixture().apply { direction = "OUTGOING" }
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        try {
            Robolectric.buildActivity(MainActivity::class.java).use { main ->
                main.create().start().resume()
                fixture.source.dial("+12025550123")
                idle()
                assertEquals(listOf("dial"), fixture.commands)
                val open = requireNotNull(shadowOf(main.get()).nextStartedActivity)
                val before = ready(fixture)
                main.pause().stop()
                Robolectric.buildActivity(CallActivity::class.java, open).use { call ->
                    call.create().start().resume()
                    idle()
                    assertFalse("equal IDLE refresh finished pending dial Activity", call.get().isFinishing)
                    assertEquals(CallPhase.Dialing, ready(fixture).phase)
                    assertSame(before.binding, ready(fixture).binding)
                    assertTrue(ready(fixture).dialBusy)
                    call.newIntent(Intent(open))
                    idle()
                    assertFalse(call.get().isFinishing)
                    assertSame(before.binding, ready(fixture).binding)
                    call.pause().stop().destroy()
                }
                main.destroy()
            }
        } finally {
            runtime.notifications.stop(); runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null; fixture.close()
        }
    }

    @Test fun confirmingOptimisticDialDoesNotAutoOpenSecondCallActivityOnResume() {
        val fixture = CallPlatformFixture().apply { direction = "OUTGOING" }
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        try {
            Robolectric.buildActivity(MainActivity::class.java).use { main ->
                main.create().start().resume()
                fixture.source.dial("+12025550123")
                idle()
                val first = requireNotNull(shadowOf(main.get()).nextStartedActivity)
                assertEquals(CallActivity::class.java.name, first.component?.className)
                val optimistic = requireNotNull(ready(fixture).binding)
                main.pause().stop()
                fixture.stage = "DIALING"
                fixture.revision++
                fixture.source.refresh()
                val confirmed = requireNotNull(ready(fixture).binding)
                assertTrue(optimistic.sameCall(confirmed))
                assertNotSame(optimistic, confirmed)
                main.restart().start().resume()
                idle()
                assertNull("token confirmation reopened the same call", shadowOf(main.get()).nextStartedActivity)
                repeat(3) {
                    fixture.source.refresh()
                    main.pause().stop().restart().start().resume()
                    idle()
                    assertNull(shadowOf(main.get()).nextStartedActivity)
                }
                main.pause().stop().destroy()
            }
        } finally {
            runtime.notifications.stop(); runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null; fixture.close()
        }
    }

    @Test fun originalOptimisticIntentStillOpensAfterTokenConfirmation() {
        val fixture = CallPlatformFixture().apply { direction = "OUTGOING" }
        RuntimeApplicationShadow.callFixture = fixture
        val context = RuntimeEnvironment.getApplication()
        check(context is SoftVoWiFiApplication)
        val runtime = context.runtime()
        try {
            Robolectric.buildActivity(MainActivity::class.java).use { main ->
                main.create().start().resume()
                fixture.source.dial("+12025550123")
                idle()
                val original = requireNotNull(shadowOf(main.get()).nextStartedActivity)
                val oldKey = requireNotNull(original.getStringExtra(
                    AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
                main.pause().stop()
                fixture.stage = "DIALING"
                fixture.revision++
                fixture.source.refresh()
                val confirmed = requireNotNull(ready(fixture).binding)
                assertNotEquals(oldKey, runtime.notifications.keyForCurrent())
                Robolectric.buildActivity(CallActivity::class.java, original).use { call ->
                    call.create().start().resume()
                    idle()
                    assertFalse(call.get().isFinishing)
                    assertSame(confirmed, runtime.notifications.resolveKey(oldKey))
                    assertTrue(shadowOf(call.get()).showWhenLocked)
                    call.pause().stop().destroy()
                }
                main.restart().start().resume()
                idle()
                assertNull(shadowOf(main.get()).nextStartedActivity)
                main.pause().stop().destroy()
            }
        } finally {
            runtime.notifications.stop(); runtime.calls.lifecycle.close()
            RuntimeApplicationShadow.callFixture = null; fixture.close()
        }
    }

    private fun ready(fixture: CallPlatformFixture): CallUiState.Ready {
        val state = fixture.source.state.value
        check(state is CallUiState.Ready)
        return state
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
