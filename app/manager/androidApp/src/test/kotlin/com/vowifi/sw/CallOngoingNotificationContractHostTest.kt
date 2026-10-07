package com.vowifi.sw

import android.app.Notification
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class CallOngoingNotificationContractHostTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun ongoingPrivateContentShowsBoundedPeerAndPublicPreviewStaysGeneric() {
        val fixture = CallPlatformFixture("IN_CALL")
        try {
            val notification = CallOngoingNotification.build(context, "opaque-call-key", ready(fixture))
            assertEquals("+12025550123", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("通话中", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(Notification.CATEGORY_CALL, notification.category)
            assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertEquals(2, notification.extras.getInt("android.callType"))
            assertNull(notification.fullScreenIntent)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            val public = notification.publicVersion
            assertEquals("SoftVoWiFi", public.extras.getString(Notification.EXTRA_TITLE))
            listOf("+12025550123", fixture.token, fixture.epoch, "opaque-call-key").forEach {
                assertFalse(public.extras.toString().contains(it))
            }
            assertFalse(public.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(public.actions.isNullOrEmpty())
            assertNull(public.contentIntent)
        } finally { fixture.close() }
    }

    @Test fun confirmedDurationAnchorsChronometerToSessionElapsedNotPostingTime() {
        val fixture = CallPlatformFixture("IN_CALL")
        try {
            val state = ready(fixture)
            val clock = requireNotNull(state.clock)
            val wallBefore = System.currentTimeMillis()
            val elapsedBefore = clock.elapsedMs()
            val notification = CallOngoingNotification.build(context, "opaque-call-key", state)
            val wallAfter = System.currentTimeMillis()
            val elapsedAfter = clock.elapsedMs()
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
            assertFalse(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertTrue(notification.`when` >= wallBefore - elapsedAfter)
            assertTrue(notification.`when` <= wallAfter - elapsedBefore)
            assertTrue(elapsedBefore >= 12_000)
        } finally { fixture.close() }
    }

    @Test fun everyPhaseHasCorrectStatusEndActionAndOnlyConfirmedInCallHasTime() {
        val fixture = CallPlatformFixture("IN_CALL")
        try {
            val base = ready(fixture)
            val labels = mapOf(CallPhase.Idle to "正在准备通话", CallPhase.Ringing to "来电", CallPhase.Connecting to "正在接通",
                CallPhase.Dialing to "正在拨号", CallPhase.InCall to "通话中", CallPhase.Canceling to "正在取消", CallPhase.Ending to "正在挂断")
            labels.forEach { (phase, label) ->
                val notification = CallOngoingNotification.build(context, "opaque-call-key", base.copy(phase = phase))
                assertEquals(label, notification.extras.getString(Notification.EXTRA_TEXT))
                assertEquals(phase == CallPhase.InCall, notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                if (phase in setOf(CallPhase.Dialing, CallPhase.Connecting, CallPhase.InCall)) {
                    val end = notification.actions.single()
                    if (phase == CallPhase.InCall) {
                        assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
                        assertTrue(end.title.isNotBlank()) // CallStyle supplies the localized hang-up button.
                    } else {
                        assertNotEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
                        assertEquals(if (phase == CallPhase.Dialing) "取消" else "挂断", end.title)
                    }
                    assertTrue(end.actionIntent.isImmutable)
                    assertTrue(end.actionIntent.isBroadcast)
                    val intent = shadowOf(end.actionIntent).savedIntent
                    assertEquals(CallOngoingNotification.END_ACTION, intent.action)
                    assertEquals(IncomingCallActionReceiver::class.java.name, intent.component?.className)
                    assertEquals("opaque-call-key", intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY))
                } else assertTrue(notification.actions.isNullOrEmpty())
                assertTrue(notification.contentIntent.isImmutable)
                assertTrue(notification.contentIntent.isActivity)
                assertEquals(CallActivity::class.java.name, shadowOf(notification.contentIntent).savedIntent.component?.className)
            }
            CallSyncState.entries.filter { it != CallSyncState.Ready }.forEach { sync ->
                val uncertain = CallOngoingNotification.build(context, "opaque-call-key", base.copy(sync = sync))
                assertEquals("通话状态待确认", uncertain.extras.getString(Notification.EXTRA_TEXT))
                assertFalse(uncertain.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                assertTrue(uncertain.actions.isNullOrEmpty())
            }
            val noClock = CallOngoingNotification.build(context, "opaque-call-key", base.copy(clock = null))
            assertFalse(noClock.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        } finally { fixture.close() }
    }

    @Test fun startupAndUnparseableCallerUseUnknownNumberWithoutPrivateSipData() {
        val startup = CallOngoingNotification.build(context, null, null)
        assertEquals("未知号码", startup.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(startup.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNull(startup.contentIntent)
        assertTrue(startup.actions.isNullOrEmpty())
        val state = CallUiState.Ready(CallPhase.InCall, CallAudioState.Idle, CallOrigin.ENGINE,
            peer = CallPeer.parse("sip:555@example.invalid"))
        val notification = CallOngoingNotification.build(context, null, state)
        assertEquals("未知号码", notification.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(notification.extras.toString().contains("sip:"))
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test fun replacedCallEndActionCannotEndTheCurrentCall() {
        val fixture = CallPlatformFixture("IN_CALL")
        RuntimeApplicationShadow.callFixture = fixture
        val application = context
        check(application is SoftVoWiFiApplication)
        val runtime = application.runtime()
        try {
            val old = CallOngoingNotification.build(context, runtime.notifications.keyForCurrent(), ready(fixture))
            fixture.epoch = "replacement-epoch"; fixture.token = "replacement-call"; fixture.revision++
            fixture.source.refresh()
            IncomingCallActionReceiver().onReceive(context, shadowOf(old.actions.single().actionIntent).savedIntent)
            assertTrue(fixture.commands.isEmpty())
            val current = CallOngoingNotification.build(context, runtime.notifications.keyForCurrent(), ready(fixture))
            IncomingCallActionReceiver().onReceive(context, shadowOf(current.actions.single().actionIntent).savedIntent)
            assertEquals(listOf("hangup"), fixture.commands)
        } finally { runtime.calls.lifecycle.close(); runtime.notifications.stop(); RuntimeApplicationShadow.callFixture = null; fixture.close() }
    }

    private fun ready(fixture: CallPlatformFixture): CallUiState.Ready {
        val state = fixture.source.state.value
        check(state is CallUiState.Ready)
        return state
    }
}
