package com.vowifi.sw

import android.os.Looper
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [CallEntryApplicationShadow::class, CallEntryShellShadow::class, CallEntryHmacShadow::class,
        CallEntrySocketShadow::class, CallEntryAudioShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallSelectedLineEntryHostTest {
    @Test fun selectedBDialsOnceOnlyAfterActualServiceAcknowledgesPreparedKey() = dialSelectedB(false)
    @Test fun recoveredSelectedBHasNoSyncNoticeAndDialsThroughRealEntry() = dialSelectedB(true)

    private fun dialSelectedB(recover: Boolean) = runBlocking {
        val fixture = CallEntryFixture()
        val activity = Robolectric.buildActivity(CallEntryActivity::class.java).setup()
        val service = Robolectric.buildService(CallMicrophoneService::class.java).create()
        try {
            fixture.connected()
            fixture.source.selectLine(fixture.ownerB)
            if (recover) {
                fixture.second.snapshotError = 3002
                fixture.source.refresh()
                val failed = fixture.ready { it.sync == CallSyncState.Unregistered }
                assertNotNull(failed.operationMessage)
                fixture.second.snapshotError = null
                fixture.source.refresh()
                fixture.connected()
            }
            val binding = fixture.source.lines.value.outgoingBinding!!
            assertSame(binding, fixture.ready { it.sync == CallSyncState.Ready }.binding)
            assertNull((fixture.source.state.value as CallUiState.Ready).operationMessage)
            assertSame(fixture.runtime, activity.get().processRuntime)
            activity.get().calls.dial(binding, NUMBER)
            shadowOf(Looper.getMainLooper()).idle()
            val intent = withTimeout(3000) { activity.get().serviceRequest.await() }
            val key = intent.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertTrue(fixture.source.lifecycle.acceptsService(key))
            assertTrue(fixture.source.lifecycle.busy.value)
            assertTrue(fixture.first.commands.isEmpty())
            assertTrue(fixture.second.commands.isEmpty())
            assertTrue(fixture.repository.listByOwner(fixture.ownerB).isEmpty())

            service.get().onStartCommand(intent, 0, 1)
            assertSame(fixture.runtime, ReflectionHelpers.getField<SoftVoWiFiRuntime>(service.get(), "runtime"))
            assertNotNull(shadowOf(service.get()).lastForegroundNotification)
            shadowOf(Looper.getMainLooper()).idle()
            val params = withTimeout(3000) { fixture.second.dialRequest.await() }
            assertEquals("tel:$NUMBER", params.getValue("recipient").jsonPrimitive.content)
            val connected = fixture.ready { it.phase == CallPhase.InCall && it.audio == CallAudioState.Active }
            assertEquals(listOf("dial"), fixture.second.commands.toList())
            assertTrue(fixture.first.commands.isEmpty())
            assertEquals(CallDualBackendFixture.ICCID_B, fixture.audioStarts.single().iccid)
            fixture.history(CallOutcome.CONNECTED)
            val record = fixture.repository.listByOwner(fixture.ownerB).single()
            assertEquals(CallDirection.OUTGOING, record.direction)
            assertEquals(NUMBER.removePrefix("+"), record.peer!!.digits())
            assertTrue(fixture.repository.listByOwner(fixture.ownerA).isEmpty())
            fixture.source.act(connected.binding!!, CallAction.Hangup)
            fixture.ready { it.phase == CallPhase.Idle }
            fixture.history(CallOutcome.COMPLETED)
            assertEquals(listOf("dial", "hangup"), fixture.second.commands.toList())
            assertFalse(fixture.source.lifecycle.busy.value)
            assertFalse(fixture.source.lifecycle.acceptsService(key))
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
        } finally { service.destroy(); activity.close(); fixture.close() }
        assertTrue(fixture.first.sockets.all { it.closed })
        assertTrue(fixture.second.sockets.all { it.closed })
    }

    @Test fun retiredBOriginalBindingCannotStartServiceOrFallBackToA() = runBlocking {
        val fixture = CallEntryFixture()
        val activity = Robolectric.buildActivity(CallEntryActivity::class.java).setup()
        try {
            fixture.connected()
            fixture.source.selectLine(fixture.ownerB)
            val old = fixture.source.lines.value.outgoingBinding!!
            fixture.secondPid = 3
            fixture.source.refresh()
            withTimeout(3000) { fixture.source.lines.first { it.outgoingBinding != null && it.outgoingBinding !== old } }
            fixture.source.selectLine(fixture.ownerA)
            activity.get().calls.dial(old, NUMBER)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(fixture.source.lifecycle.feedback.value)
            assertFalse(activity.get().serviceRequest.isCompleted)
            assertTrue(fixture.first.commands.isEmpty())
            assertTrue(fixture.second.commands.isEmpty())
            assertTrue(fixture.repository.listByOwner(fixture.ownerA).isEmpty())
            assertTrue(fixture.repository.listByOwner(fixture.ownerB).isEmpty())
        } finally { activity.close(); fixture.close() }
    }

    companion object { private const val NUMBER = "+12025550123" }
}
