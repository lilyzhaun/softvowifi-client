package com.vowifi.sw

import kotlinx.coroutines.CompletableDeferred
import com.vowifi.sw.manager.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HomeEngineControlOwnershipHostTest {
    @Test fun appOnlyCallProtectionUsesSubscriptionHandleWithoutBackendWorkOrCrossLineLeak() {
        HomeEngineControlHostFixture().use { fixture ->
            assertEquals("IDLE", fixture.row(0).runtime?.call)
            val binding = CallBinding::class.java.declaredConstructors.single { it.parameterCount == 4 }
                .newInstance(HomeEngineControlHostFixture.OWNER_A, "synthetic-call", 1L, "synthetic-epoch") as CallBinding
            val call = CallUiState.Ready(CallPhase.InCall, CallAudioState.Idle, CallOrigin.ENGINE, binding = binding)
            val commands = fixture.commands.toList(); val requests = fixture.requests.toList()
            assertEquals(mapOf(fixture.row(0).handle to 0), fixture.sims.activeCallSims(call))
            assertTrue(fixture.sims.activeCallSims(call.copy(phase = CallPhase.Idle)).isEmpty())
            assertEquals(commands, fixture.commands); assertEquals(requests, fixture.requests)
            fixture.subscriptions(includeFirst = false); fixture.sims.refresh(); fixture.dispatcher.runCurrent()
            assertTrue("移除SIM后不能把同一通话归给另一条线路", fixture.sims.activeCallSims(call).isEmpty())
            fixture.assertBPreserved()
        }
    }

    @Test fun removedSubscriptionHandleCannotStopAnyOwner() {
        HomeEngineControlHostFixture().use { fixture ->
            val old = fixture.row(0).handle
            fixture.subscriptions(includeFirst = false)
            fixture.sims.refresh()
            fixture.dispatcher.runCurrent()
            assertEquals(1, fixture.ready().instances.size)
            fixture.sims.stop(old)
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.commands.none { it.first == "stop" })
            assertTrue(fixture.requests.none { it.second == "stop" })
            fixture.assertBPreserved()
        }
    }

    @Test fun closingHeldRefreshCancelsQueuedStopWithoutFurtherBackendWork() {
        HomeEngineControlHostFixture().use { fixture ->
            fixture.statusGate = CompletableDeferred()
            fixture.engine.refresh()
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.statusEntered.isCompleted)
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            val requests = fixture.requests.toList()
            val commands = fixture.commands.toList()
            fixture.engine.close()
            fixture.dispatcher.runCurrent()
            assertEquals(requests, fixture.requests)
            assertEquals(commands, fixture.commands)
            assertTrue(fixture.removed.isEmpty())
            assertEquals(1, fixture.backendCloses)
        }
    }

    @Test fun closingStopRpcDoesNotRunShellFallbackAfterCancellation() {
        HomeEngineControlHostFixture().use { fixture ->
            fixture.stopGate = CompletableDeferred()
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.stopEntered.isCompleted)
            val commands = fixture.commands.toList()
            fixture.engine.close()
            fixture.dispatcher.runCurrent()
            assertEquals(commands, fixture.commands)
            assertTrue(fixture.removed.isEmpty())
            assertEquals(1, fixture.backendCloses)
        }
    }
}
