package com.vowifi.sw

import android.Manifest
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallPreparedDialRosterHostTest {
    @Test fun removedOwnerAfterPrepareCannotDialOrFallBack() = rejectedDial { fixture ->
        fixture.activeRoster = listOf(CallDualBackendFixture.ICCID_B)
        fixture.source.refresh()
        assertTrue(fixture.source.lines.value.rosterComplete)
        assertFalse(fixture.source.lines.value.choices.single { it.owner == fixture.ownerA }.active)
    }

    @Test fun rosterReadFailureAfterPrepareCannotDialOrFallBack() = rejectedDial { fixture ->
        fixture.rosterFailure = true
        fixture.source.refresh()
        assertFalse(fixture.source.lines.value.rosterComplete)
        assertTrue(fixture.source.lines.value.choices.none { it.available })
    }

    @Test fun unknownRosterAfterPrepareCannotDialOrFallBack() = rejectedDial { fixture ->
        fixture.activeRoster = null
        fixture.source.refresh()
        assertFalse(fixture.source.lines.value.rosterComplete)
        assertTrue(fixture.source.lines.value.choices.all { it.available })
    }

    @Test fun unchangedRosterDispatchesExactlyOneDialToOriginalOwner() = acceptedDial(browseOther = false)

    @Test fun browsingOtherLineDoesNotRetargetOrCancelOriginalPreparedDial() = acceptedDial(browseOther = true)

    @Test fun answerGrantSurvivesRosterReadFailureDuringServiceBarrier() = withFixture { fixture, activity ->
        fixture.first.incoming("RINGING")
        val ready = fixture.waitReady { it.phase == CallPhase.Ringing }
        val binding = ready.binding!!
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.lifecycle.perform(activity, binding, MicrophoneCallAction.Answer)
        }
        assertHeld(fixture, result)
        fixture.rosterFailure = true
        fixture.source.refresh()
        assertSame(binding, fixture.ready()!!.binding)
        assertTrue(fixture.source.lines.value.choices.none { it.available })
        acknowledge(fixture, activity)
        assertTrue(withTimeout(3000) { result.await() })
        fixture.waitReady { it.phase == CallPhase.Connecting }
        assertEquals(listOf("answer"), fixture.first.commands.toList())
        assertTrue(fixture.second.commands.isEmpty())
    }

    @Test fun restoreGrantSurvivesUnknownRosterDuringServiceBarrier() = withFixture { fixture, activity ->
        fixture.first.incoming("IN_CALL")
        val ready = fixture.waitReady { it.phase == CallPhase.InCall && it.audio is CallAudioState.Failed }
        val binding = ready.binding!!
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.lifecycle.perform(activity, binding, MicrophoneCallAction.Restore)
        }
        assertHeld(fixture, result)
        fixture.activeRoster = null
        fixture.source.refresh()
        assertSame(binding, fixture.ready()!!.binding)
        assertFalse(fixture.source.lines.value.rosterComplete)
        acknowledge(fixture, activity)
        assertTrue(withTimeout(3000) { result.await() })
        fixture.waitReady { it.audio == CallAudioState.Active }
        assertEquals(CallDualBackendFixture.ICCID_A, fixture.starts.single().iccid)
        assertTrue(fixture.first.commands.isEmpty())
        assertTrue(fixture.second.commands.isEmpty())
    }

    private fun rejectedDial(changeRoster: (CallDualBackendFixture) -> Unit) = withFixture { fixture, activity ->
        val binding = fixture.source.lines.value.outgoingBinding!!
        val instances = fixture.instances
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.lifecycle.perform(activity, binding, MicrophoneCallAction.Dial, "+12025550123")
        }
        assertHeld(fixture, result)
        changeRoster(fixture)
        assertSame(instances, fixture.instances)
        assertSame(binding, fixture.ready()!!.binding)
        assertEquals(CallSyncState.Ready, fixture.ready()!!.sync)
        assertNull(fixture.source.lines.value.outgoingBinding)
        acknowledge(fixture, activity)
        val accepted = withTimeout(3000) { result.await() }
        if (accepted) fixture.waitReady { it.phase == CallPhase.InCall }
        assertEquals(emptyList<String>(), fixture.first.commands.toList())
        assertEquals(emptyList<String>(), fixture.second.commands.toList())
        assertFalse(accepted)
        assertTrue(fixture.starts.isEmpty())
        assertFalse(fixture.microphone.foreground)
    }

    private fun acceptedDial(browseOther: Boolean) = withFixture { fixture, activity ->
        val binding = fixture.source.lines.value.outgoingBinding!!
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.lifecycle.perform(activity, binding, MicrophoneCallAction.Dial, "+12025550123")
        }
        assertHeld(fixture, result)
        fixture.source.refresh()
        assertSame(binding, fixture.ready()!!.binding)
        if (browseOther) fixture.source.selectLine(fixture.ownerB)
        assertTrue(fixture.source.lines.value.choices.single { it.owner == fixture.ownerA }.active)
        assertNull(fixture.source.lines.value.outgoingBinding)
        acknowledge(fixture, activity)
        assertTrue(withTimeout(3000) { result.await() })
        fixture.waitReady { it.phase == CallPhase.InCall && it.audio == CallAudioState.Active }
        assertEquals(listOf("dial"), fixture.first.commands.toList())
        assertTrue(fixture.second.commands.isEmpty())
        assertEquals(CallDualBackendFixture.ICCID_A, fixture.starts.single().iccid)
        assertEquals(if (browseOther) fixture.ownerB else fixture.ownerA, fixture.source.lines.value.selectedOwner)
    }

    private fun assertHeld(fixture: CallDualBackendFixture, result: Deferred<Boolean>) {
        assertFalse(result.isCompleted)
        assertTrue(fixture.lifecycle.busy.value)
        assertFalse(fixture.microphone.foreground)
        assertTrue(fixture.first.commands.isEmpty())
        assertTrue(fixture.second.commands.isEmpty())
    }

    private fun acknowledge(fixture: CallDualBackendFixture, activity: CallGateActivity) {
        val key = activity.serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
        assertTrue(fixture.lifecycle.serviceStarted(key) {})
    }

    private suspend fun CallDualBackendFixture.waitReady(predicate: (CallUiState.Ready) -> Boolean): CallUiState.Ready =
        withTimeout(3000) { source.state.first { it is CallUiState.Ready && predicate(it) } as CallUiState.Ready }

    private fun withFixture(block: suspend CoroutineScope.(CallDualBackendFixture, CallGateActivity) -> Unit) = runBlocking {
        val fixture = CallDualBackendFixture()
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        shadowOf(fixture.context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        try {
            withTimeout(3000) { fixture.source.lines.first { it.choices.count { line -> line.syncReady } == 2 } }
            fixture.source.selectLine(fixture.ownerA)
            fixture.lifecycle.setVisible(activity.get(), true)
            block(fixture, activity.get())
        } finally { fixture.close(); activity.close() }
    }
}
