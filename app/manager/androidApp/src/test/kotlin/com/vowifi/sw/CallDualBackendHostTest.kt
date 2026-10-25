package com.vowifi.sw

import android.Manifest
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.CallDualBackendFixture.Companion.await
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallDualBackendHostTest {
    @Test fun actualLifecycleDialsBAndLateServiceCallbackCannotRevokeRestoredLease() = runBlocking {
        val fixture = CallDualBackendFixture()
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        shadowOf(fixture.context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        try {
            fixture.connected()
            assertEquals(fixture.ownerA, fixture.source.lines.value.selectedOwner)
            assertNotNull(fixture.source.lines.value.outgoingBinding)
            fixture.source.selectLine(fixture.ownerB)
            fixture.lifecycle.setVisible(activity.get(), true)
            val binding = fixture.source.lines.value.outgoingBinding!!
            val dial = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.lifecycle.perform(activity.get(), binding, MicrophoneCallAction.Dial, "+12025550123")
            }
            val oldKey = activity.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertTrue(fixture.second.commands.isEmpty())
            assertTrue(fixture.lifecycle.serviceStarted(oldKey) {})
            assertTrue(withTimeout(4000) { dial.await() })
            await { fixture.starts.size == 1 && fixture.ready()?.audio == CallAudioState.Active }
            assertEquals(listOf("dial"), fixture.second.commands.toList())
            assertTrue(fixture.first.commands.isEmpty())
            val session = fixture.starts.single()
            assertEquals(CallDualBackendFixture.ICCID_B, session.iccid)
            fixture.audioEvents.emit(CallAudioEvent.Ended(session))
            await { fixture.ready()?.audio is CallAudioState.Failed }
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.lifecycle.perform(activity.get(), fixture.ready()!!.binding!!, MicrophoneCallAction.Restore)
            }
            val newKey = activity.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertNotEquals(oldKey, newKey)
            assertTrue(fixture.lifecycle.serviceStarted(newKey) {})
            assertTrue(withTimeout(4000) { restore.await() })
            await { fixture.starts.size == 2 }
            fixture.lifecycle.serviceDestroyed(oldKey)
            fixture.lifecycle.serviceFailed(oldKey)
            assertTrue(fixture.microphone.permits(fixture.starts.last()))
            fixture.source.hangup()
            await { fixture.ready()?.phase == CallPhase.Idle }
            await { fixture.repository.listByOwner(fixture.ownerB).singleOrNull()?.outcome == CallOutcome.COMPLETED }
            assertTrue(fixture.repository.listByOwner(fixture.ownerA).isEmpty())
        } finally { fixture.close(); activity.close() }
    }

    @Test fun selectedAStillReceivesAndAnswersBThroughRealSocketAndRoom() = runBlocking {
        val fixture = CallDualBackendFixture()
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        shadowOf(fixture.context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        try {
            fixture.connected(); fixture.source.selectLine(fixture.ownerA)
            fixture.lifecycle.setVisible(activity.get(), true)
            fixture.second.incoming("RINGING")
            await { fixture.ready()?.phase == CallPhase.Ringing }
            val binding = fixture.ready()!!.binding!!
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.lifecycle.perform(activity.get(), binding, MicrophoneCallAction.Answer)
            }
            val key = activity.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertTrue(fixture.lifecycle.serviceStarted(key) {})
            assertTrue(withTimeout(4000) { answer.await() })
            await { fixture.second.commands.contains("answer") }
            fixture.second.incoming("IN_CALL")
            fixture.connectedAudioSnapshot()
            assertTrue(fixture.first.commands.isEmpty())
            assertEquals(fixture.ownerA, fixture.source.lines.value.selectedOwner)
            fixture.second.incoming("ENDED", "remote")
            await { fixture.repository.listByOwner(fixture.ownerB).singleOrNull()?.outcome == CallOutcome.COMPLETED }
        } finally { fixture.close(); activity.close() }
    }

    @Test fun audioStartCannotCompleteTheFixtureBarrierWhileItsRealClockSnapshotIsHeld() = runBlocking {
        val fixture = CallDualBackendFixture()
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        shadowOf(fixture.context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val snapshotReply = CompletableDeferred<() -> Unit>()
        try {
            fixture.connected(); fixture.source.selectLine(fixture.ownerA)
            fixture.lifecycle.setVisible(activity.get(), true)
            fixture.second.incoming("RINGING")
            await { fixture.ready()?.phase == CallPhase.Ringing }
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.lifecycle.perform(activity.get(), fixture.ready()!!.binding!!, MicrophoneCallAction.Answer)
            }
            val key = activity.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertTrue(fixture.lifecycle.serviceStarted(key) {})
            assertTrue(withTimeout(4000) { answer.await() })
            withTimeout(8000) {
                // Lifecycle acceptance only dispatches answer; the peer must receive it before
                // this test advances the real call to IN_CALL, just like the adjacent test.
                await { fixture.second.commands.contains("answer") }
                assertEquals(1, fixture.second.commands.count { it == "answer" })
                fixture.second.onSnapshotReply = { check(snapshotReply.complete(it)) }
                fixture.second.incoming("IN_CALL")
                await { fixture.starts.size == 1 && snapshotReply.isCompleted }
            }
            assertEquals(CallPhase.InCall, fixture.ready()?.phase)
            assertNull("前提：真实IN_CALL时钟快照仍被阻挡，不能以audio.start代其完成", fixture.ready()?.clock)
            val connected = async(start = CoroutineStart.UNDISPATCHED) { fixture.connectedAudioSnapshot() }
            assertFalse("夹具必须等待实际时钟快照，而不是audio.start被调用", connected.isCompleted)
            fixture.second.onSnapshotReply = null
            snapshotReply.await().invoke()
            withTimeout(8000) { connected.await() }
            assertNotNull(fixture.ready()?.clock)
            fixture.second.incoming("ENDED", "remote")
            await { fixture.repository.listByOwner(fixture.ownerB).singleOrNull()?.outcome == CallOutcome.COMPLETED }
            assertTrue(fixture.first.commands.isEmpty())
            assertEquals(fixture.ownerA, fixture.source.lines.value.selectedOwner)
        } finally {
            val current = fixture.ready()
            println("CALL_CLOCK_GATE|starts=${fixture.starts.size}|snapshotHeld=${snapshotReply.isCompleted}|phase=${current?.phase}|sync=${current?.sync}|clock=${current?.clock != null}|firstMethods=${fixture.first.commands}|secondMethods=${fixture.second.commands}")
            fixture.close(); activity.close()
        }
    }

    @Test fun reversedStreamsPersistBothOwnersWithSameTokenAndGenerationInRoom() = runBlocking {
        val fixture = CallDualBackendFixture()
        try {
            fixture.connected(); fixture.source.selectLine(fixture.ownerA)
            fixture.first.incoming("RINGING")
            await { fixture.ready()?.phase == CallPhase.Ringing }
            val old = fixture.ready()!!.binding!!
            fixture.first.stage = "IDLE"; fixture.first.revision++
            fixture.second.incoming("RINGING")
            await { fixture.ready()?.binding !== old && fixture.ready()?.phase == CallPhase.Ringing }
            fixture.first.incoming("ENDED", "timeout")
            fixture.second.incoming("ENDED", "timeout")
            await { fixture.repository.listByOwner(fixture.ownerA).singleOrNull()?.endedAtMs != null &&
                fixture.repository.listByOwner(fixture.ownerB).singleOrNull()?.outcome == CallOutcome.MISSED }
            assertEquals(1, fixture.repository.listByOwner(fixture.ownerA).size)
            assertEquals(1, fixture.repository.listByOwner(fixture.ownerB).size)
        } finally { fixture.close() }
    }
}
