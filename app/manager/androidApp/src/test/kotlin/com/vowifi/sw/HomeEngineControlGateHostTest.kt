package com.vowifi.sw

import com.vowifi.sw.HomeEngineControlHostFixture.Companion.OWNER_A
import com.vowifi.sw.HomeEngineControlHostFixture.Companion.OWNER_B
import com.vowifi.sw.manager.model.EngineLifecycle
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HomeEngineControlGateHostTest {
    @Test fun freeGateHomeStopUsesOriginalHandleAndPreservesB() {
        HomeEngineControlHostFixture().use { fixture ->
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            assertStoppedOnce(fixture)
        }
    }

    @Test fun heldRefreshCannotDropValidHomeStop() {
        HomeEngineControlHostFixture().use { fixture ->
            val barrier = heldRefresh(fixture)
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.commands.none { it.first == "stop" })
            barrier.complete(Unit)
            fixture.dispatcher.runCurrent()
            assertStoppedOnce(fixture)
        }
    }

    @Test fun failedRefreshStatusStillReleasesQueuedHomeStop() {
        HomeEngineControlHostFixture().use { fixture ->
            val barrier = heldRefresh(fixture)
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            barrier.completeExceptionally(IOException("synthetic status failure"))
            fixture.dispatcher.runCurrent()
            assertStoppedOnce(fixture)
        }
    }

    @Test fun failedRefreshListStillReleasesPreviouslyAcceptedHomeStop() {
        HomeEngineControlHostFixture().use { fixture ->
            val barrier = CompletableDeferred<Unit>().also { fixture.listGate = it }
            fixture.engine.refresh()
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.listEntered.isCompleted)
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            barrier.completeExceptionally(IOException("synthetic list failure"))
            fixture.dispatcher.runCurrent()
            assertStoppedOnce(fixture)
        }
    }

    @Test fun failedUserStartReleasesQueuedStopOnOtherOwnerWithoutRetry() {
        HomeEngineControlHostFixture(firstRunning = false).use { fixture ->
            val barrier = CompletableDeferred<Unit>().also { fixture.startGate = it }
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.startEntered.isCompleted)
            fixture.homeAction(1)
            fixture.dispatcher.runCurrent()
            barrier.completeExceptionally(IOException("synthetic start failure"))
            fixture.dispatcher.runCurrent()
            assertEquals(listOf("start" to OWNER_A, "stop" to OWNER_B), fixture.commands.filter { it.first != "list" })
            assertEquals(EngineLifecycle.STOPPED, fixture.row(1).lifecycle)
            assertEquals(1, fixture.maxInFlight)
        }
    }

    @Test fun duplicateQueuedHomeStopsExecuteOnce() {
        HomeEngineControlHostFixture().use { fixture ->
            val barrier = heldRefresh(fixture)
            repeat(2) { fixture.homeAction(0) }
            fixture.dispatcher.runCurrent()
            barrier.complete(Unit)
            fixture.dispatcher.runCurrent()
            assertStoppedOnce(fixture)
        }
    }

    @Test fun failedShellStopIsNotReplayedAndReleasesOtherOwnerStop() {
        HomeEngineControlHostFixture().use { fixture ->
            val barrier = CompletableDeferred<Unit>().also { fixture.stopGate = it }
            fixture.shellStopFailureOwner = OWNER_A
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.stopEntered.isCompleted)
            fixture.homeAction(1)
            fixture.dispatcher.runCurrent()
            barrier.complete(Unit)
            fixture.dispatcher.runCurrent()
            assertEquals(listOf("stop" to OWNER_A, "stop" to OWNER_B), fixture.commands.filter { it.first != "list" })
            assertEquals(EngineLifecycle.RUNNING, fixture.row(0).lifecycle)
            assertEquals(EngineLifecycle.STOPPED, fixture.row(1).lifecycle)
            assertEquals(1, fixture.maxInFlight)
        }
    }

    @Test fun duplicateQueuedHomeStartsDoNotRetryFailedStart() {
        HomeEngineControlHostFixture(firstRunning = false).use { fixture ->
            val refresh = heldRefresh(fixture)
            val starting = CompletableDeferred<Unit>().also { fixture.startGate = it }
            repeat(2) { fixture.homeAction(0) }
            fixture.dispatcher.runCurrent()
            refresh.complete(Unit)
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.startEntered.isCompleted)
            starting.completeExceptionally(IOException("synthetic start failure"))
            fixture.dispatcher.runCurrent()
            assertEquals(listOf("start" to OWNER_A), fixture.commands.filter { it.first != "list" })
            fixture.assertBPreserved()
        }
    }

    @Test fun staleHomeProjectionCannotStartAnAlreadyStartedOwnerAgain() {
        HomeEngineControlHostFixture(firstRunning = false).use { fixture ->
            fixture.homeAction(0)
            fixture.dispatcher.runNext()
            assertTrue(fixture.row(0).canStart)
            fixture.homeAction(0)
            fixture.dispatcher.runCurrent()
            assertEquals(listOf("start" to OWNER_A), fixture.commands.filter { it.first != "list" })
            assertEquals(EngineLifecycle.RUNNING, fixture.row(0).lifecycle)
            fixture.assertBPreserved()
        }
    }

    private fun heldRefresh(fixture: HomeEngineControlHostFixture): CompletableDeferred<Unit> {
        val barrier = CompletableDeferred<Unit>().also { fixture.statusGate = it }
        fixture.engine.refresh()
        fixture.dispatcher.runCurrent()
        assertTrue(fixture.statusEntered.isCompleted)
        assertTrue(fixture.row(1).canStop)
        return barrier
    }

    private fun assertStoppedOnce(fixture: HomeEngineControlHostFixture) {
        assertEquals(listOf("stop" to OWNER_A), fixture.commands.filter { it.first != "list" })
        assertEquals(listOf(OWNER_A to "stop"), fixture.requests.filter { it.second == "stop" })
        assertEquals(EngineLifecycle.STOPPED, fixture.row(0).lifecycle)
        fixture.assertBPreserved()
    }
}
