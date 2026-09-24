package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*

class CallRecorderConcurrencyTest {
    @Test fun publishedCollectorCanJoinConcurrentRecorderMutation() = runBlocking {
        val recorder = CallHistoryRecorder(CallHistoryBinding(FakeCallHistoryRepository(),
            { if (it == "a") OWNER_A else OWNER_B }, { "concurrent" }), { 1000 }, Dispatchers.Unconfined)
        val done = CompletableDeferred<Boolean>()
        val observer = CoroutineScope(Dispatchers.Unconfined).launch {
            recorder.state.collect { state ->
                if (state is CallHistoryState.Ready && !done.isCompleted) {
                    val contender = thread { recorder.claimDial("b", "123", 2) }
                    contender.join(2000)
                    done.complete(!contender.isAlive)
                }
            }
        }
        try {
            recorder.claimDial("a", "123", 1)?.let(recorder::enqueue)
            assertTrue(withTimeout(5000) { done.await() })
        } finally { observer.cancelAndJoin(); recorder.close() }
    }

    @Test fun ownerResolverMayJoinAnotherRecorderCaller() = runBlocking {
        val entered = AtomicBoolean()
        lateinit var recorder: CallHistoryRecorder
        recorder = CallHistoryRecorder(CallHistoryBinding(FakeCallHistoryRepository(), {
            if (entered.compareAndSet(false, true)) {
                val contender = thread { recorder.claimDial("b", "123", 2) }
                contender.join(2000)
                assertFalse(contender.isAlive)
            }
            if (it == "a") OWNER_A else OWNER_B
        }, { "concurrent" }), { 1000 }, Dispatchers.Default)
        try { assertNotNull(recorder.claimDial("a", "123", 1)); Unit } finally { recorder.close() }
    }

    @Test fun twoChildThreadsHaveDistinctSequencesAndExactTerminalRows() = runBlocking {
        val recorder = CallHistoryRecorder(CallHistoryBinding(FakeCallHistoryRepository(),
            { if (it == "a") OWNER_A else OWNER_B }, { "concurrent" }), { 1000 }, Dispatchers.Default)
        val effects = ConcurrentLinkedQueue<CallHistoryEffect>()
        try {
            withTimeout(5000) {
                listOf("a", "b").map { owner -> async(Dispatchers.Default) {
                    repeat(20) { index ->
                        val generation = index.toLong()
                        val ringing = CallSession(token = "same-$index", phase = CallPhase.Ringing)
                        effects += assertNotNull(recorder.observe(owner, generation, CallSession(), ringing, CallHistoryCause.Session))
                        effects += assertNotNull(recorder.observe(owner, generation, ringing, CallSession(), CallHistoryCause.Reject))
                    }
                } }.awaitAll()
            }
            assertEquals(80, effects.map { it.sequence }.distinct().size)
            assertEquals(40, effects.count { it.write.outcome == CallOutcome.REJECTED })
        } finally { recorder.close() }
    }
}
