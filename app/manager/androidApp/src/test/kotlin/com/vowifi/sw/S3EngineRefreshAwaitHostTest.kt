package com.vowifi.sw

import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.EngineControlUiState
import com.vowifi.sw.manager.model.EngineControlAction
import com.vowifi.sw.manager.model.EngineControlOutcome
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 完成结果只由实际 source-owned producer 返回；保留原能力缺失时的行为断言。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class S3EngineRefreshAwaitHostTest {
    @Test fun actualListReadFailureReturnsFailedRatherThanACompletedObservation() = runBlocking {
        HomeEngineControlHostFixture().use { fixture ->
            val callers = CoroutineScope(SupervisorJob() + fixture.dispatcher)
            val hold = CompletableDeferred<Unit>().also { fixture.listGate = it }
            try {
                fixture.engine.refresh()
                fixture.dispatcher.runCurrent()
                assertTrue(fixture.listEntered.isCompleted)
                val user = callers.async { fixture.engine.refreshAndAwait() }
                fixture.dispatcher.runCurrent()
                assertFalse(user.isCompleted)
                hold.completeExceptionally(IOException("synthetic list failure"))
                fixture.dispatcher.runCurrent()
                assertTrue(user.isCompleted)
                assertTrue(user.await() is RefreshResult.Failed)
                assertTrue(fixture.engine.state.value is EngineControlUiState.Failure)
                assertTrue(fixture.commands.none { it.first == "start" || it.first == "stop" })
            } finally { hold.complete(Unit); callers.cancel(); fixture.dispatcher.runCurrent() }
        }
    }

    @Test fun acceptedControlFailureIsTypedOnceAndPollingCannotGenerateAnotherOutcome() {
        HomeEngineControlHostFixture(firstRunning = false).use { fixture ->
            val hold = CompletableDeferred<Unit>().also { fixture.startGate = it }
            try {
                fixture.homeAction(0)
                fixture.dispatcher.runCurrent()
                assertTrue(fixture.startEntered.isCompleted)
                assertTrue(fixture.row(0).operationPending)
                assertNull(fixture.row(0).controlCompletion)
                hold.completeExceptionally(IOException("synthetic start failure"))
                fixture.dispatcher.runCurrent()
                val completed = checkNotNull(fixture.row(0).controlCompletion)
                assertEquals(EngineControlAction.Start, completed.action)
                assertEquals(EngineControlOutcome.Failed, completed.outcome)
                assertFalse(fixture.row(0).operationPending)
                repeat(2) { fixture.engine.refresh(); fixture.dispatcher.runCurrent() }
                assertEquals(completed, fixture.row(0).controlCompletion)
                assertEquals(1, fixture.commands.count { it.first == "start" })
                fixture.assertBPreserved()
            } finally { hold.complete(Unit); fixture.dispatcher.runCurrent() }
        }
    }

    @Test fun closeCancelsHeldRefreshWaitersAndLaterRefreshIsUnsupported() = runBlocking {
        HomeEngineControlHostFixture().use { fixture ->
            val callers = CoroutineScope(SupervisorJob() + fixture.dispatcher)
            val hold = CompletableDeferred<Unit>().also { fixture.statusGate = it }
            try {
                fixture.engine.refresh()
                fixture.dispatcher.runCurrent()
                assertTrue(fixture.statusEntered.isCompleted)
                val user = callers.async { fixture.engine.refreshAndAwait() }
                fixture.dispatcher.runCurrent()
                assertFalse(user.isCompleted)
                val before = fixture.engine.state.value
                fixture.engine.close()
                fixture.dispatcher.runCurrent()
                assertTrue(user.isCancelled)
                assertEquals(RefreshResult.Unsupported, fixture.engine.refreshAndAwait())
                assertEquals(before, fixture.engine.state.value)
            } finally { hold.complete(Unit); callers.cancel(); fixture.dispatcher.runCurrent() }
        }
    }

    @Test fun automaticReadIsJoinedUntilPublishAndCancellingOneWaiterDoesNotCancelIt() = runBlocking {
        HomeEngineControlHostFixture().use { fixture ->
            val callers = CoroutineScope(SupervisorJob() + fixture.dispatcher)
            val hold = CompletableDeferred<Unit>().also { fixture.statusGate = it }
            val lists = fixture.commands.count { it.first == "list" }
            val before = fixture.engine.state.value
            try {
                fixture.engine.refresh()
                fixture.dispatcher.runCurrent()
                assertTrue(fixture.statusEntered.isCompleted)
                val first = callers.async { fixture.engine.refreshAndAwait() }
                val duplicate = callers.async { fixture.engine.refreshAndAwait() }
                val cancelled = callers.async { fixture.engine.refreshAndAwait() }
                fixture.dispatcher.runCurrent()
                assertFalse(first.isCompleted)
                assertFalse(duplicate.isCompleted)
                cancelled.cancel()
                fixture.dispatcher.runCurrent()
                assertTrue(cancelled.isCancelled)
                assertFalse(hold.isCancelled)
                assertEquals(lists + 1, fixture.commands.count { it.first == "list" })
                hold.complete(Unit)
                fixture.dispatcher.runCurrent()
                assertTrue(first.isCompleted)
                assertTrue(duplicate.isCompleted)
                assertEquals(RefreshResult.Completed, first.await())
                assertEquals(first.await(), duplicate.await())
                assertEquals(before, fixture.engine.state.value) // 同值读回也有完成，不等 StateFlow 发射。
                fixture.assertBPreserved()
            } finally { hold.complete(Unit); callers.cancel(); fixture.dispatcher.runCurrent() }
        }
    }

    @Test fun statusReadFailureIsFailedEvenWhenDomainStateIsReady() = runBlocking {
        HomeEngineControlHostFixture().use { fixture ->
            val callers = CoroutineScope(SupervisorJob() + fixture.dispatcher)
            val hold = CompletableDeferred<Unit>().also { fixture.statusGate = it }
            try {
                // 先建立当前真实 IO 前提；默认 Unsupported 不应被记成“屏障未进入”的准备失败。
                fixture.engine.refresh()
                fixture.dispatcher.runCurrent()
                assertTrue(fixture.statusEntered.isCompleted)
                val user = callers.async { fixture.engine.refreshAndAwait() }
                fixture.dispatcher.runCurrent()
                assertFalse(user.isCompleted)
                hold.completeExceptionally(IOException("synthetic status failure"))
                fixture.dispatcher.runCurrent()
                assertTrue(user.isCompleted)
                assertTrue(user.await() is RefreshResult.Failed)
                assertTrue(fixture.engine.state.value is EngineControlUiState.Ready)
                assertNotNull(fixture.row(0).message)
                assertNull("状态读取失败不等于已接受控制动作失败", fixture.row(0).controlCompletion)
                fixture.assertBPreserved()
            } finally { hold.complete(Unit); callers.cancel(); fixture.dispatcher.runCurrent() }
        }
    }

    @Test fun actualStartOwnsGateSoRefreshReturnsBusyWithoutReplayingStart() = runBlocking {
        HomeEngineControlHostFixture(firstRunning = false).use { fixture ->
            val callers = CoroutineScope(SupervisorJob() + fixture.dispatcher)
            val hold = CompletableDeferred<Unit>().also { fixture.startGate = it }
            val lists = fixture.commands.count { it.first == "list" }
            try {
                fixture.homeAction(0)
                fixture.dispatcher.runCurrent()
                assertTrue(fixture.startEntered.isCompleted)
                val user = callers.async { fixture.engine.refreshAndAwait() }
                fixture.dispatcher.runCurrent()
                assertTrue(user.isCompleted)
                assertEquals(RefreshResult.Busy, user.await())
                assertEquals(lists, fixture.commands.count { it.first == "list" })
                hold.complete(Unit)
                fixture.dispatcher.runCurrent()
                assertEquals(1, fixture.commands.count { it.first == "start" })
                fixture.assertBPreserved()
            } finally { hold.complete(Unit); callers.cancel(); fixture.dispatcher.runCurrent() }
        }
    }
}
