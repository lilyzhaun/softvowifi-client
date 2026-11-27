package com.vowifi.sw

import com.vowifi.sw.manager.data.RefreshResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S3ConfigurationProducerBarrierHostTest {
    @Test fun detachedProducerWithClearedFlightStillMustReleaseItsActualOperationGate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finishing = S3IoHold()
        val gate = Mutex(locked = true)
        val released = CompletableDeferred<Unit>()
        val producer = scope.launch(start = CoroutineStart.LAZY) { }
        producer.invokeOnCompletion {
            finishing.block()
            gate.unlock()
            released.complete(Unit)
        }
        try {
            producer.start()
            finishing.awaitEntered()
            assertTrue(scope.coroutineContext[Job]!!.children.none())
            val ready = async(start = CoroutineStart.UNDISPATCHED) { awaitS3ConfigurationProducer(scope, null, gate) }
            assertFalse("refreshInFlight已清且Job已脱离，但实际操作门尚未释放，夹具不能认Ready", ready.isCompleted)
            finishing.release()
            withTimeout(3000) { ready.await() }
            assertFalse(gate.isLocked)
        } finally {
            finishing.release()
            try { withTimeout(3000) { released.await() } } finally { scope.cancel() }
        }
    }

    @Test fun detachedProducerStillRequiresItsActualRefreshCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finishing = S3IoHold()
        val completion = CompletableDeferred<RefreshResult>()
        val producer = scope.launch(start = CoroutineStart.LAZY) { }
        producer.invokeOnCompletion {
            finishing.block()
            completion.complete(RefreshResult.Completed)
        }
        try {
            producer.start()
            finishing.awaitEntered()
            assertTrue("前提：真实Job已从父树移除，但完成回调仍由原屏障持有", scope.coroutineContext[Job]!!.children.none())
            val ready = async(start = CoroutineStart.UNDISPATCHED) { awaitS3ConfigurationProducer(scope, completion) }
            assertFalse("夹具不能用空children/Job.join把尚未释放的刷新completion认作就绪", ready.isCompleted)
            finishing.release()
            withTimeout(3000) { ready.await() }
            assertTrue(completion.isCompleted)
        } finally {
            finishing.release()
            try { withTimeout(3000) { completion.await() } } finally { scope.cancel() }
        }
    }
}
