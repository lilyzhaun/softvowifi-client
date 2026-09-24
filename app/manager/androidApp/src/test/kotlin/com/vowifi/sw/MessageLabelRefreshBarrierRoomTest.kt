package com.vowifi.sw

import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageSource
import com.vowifi.sw.manager.data.MessageOwnerLabel
import com.vowifi.sw.manager.data.MessageOwnerRemark
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.SimOwnerId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageLabelRefreshBarrierRoomTest {
    @Test fun refreshCancelsSuspendedLabelsAndPublishesReplacementWithoutLosingRemarks() = runBlocking {
        SmsRoomBoundaryFixture().use { fixture ->
            val stored = fixture.open()
            var source: EngineMessageSource? = null
            try {
                stored.insertIncomingIfAbsent(fixture.ownerA, "history", "wire", "155501", "synthetic", 1, 1)
                val original = stored.saveOwnerRemark(fixture.ownerA, MessageOwnerRemark.parse("synthetic remark"))
                val reads = LabelReadBarrier(stored)
                val exception = AtomicReference<Throwable?>(null)
                val running = EngineMessageSource(
                    EngineMessageRuntime({ emptyList() }, { error("no live transport") }, { 100 }, { listOf("a", "b") }),
                    Dispatchers.IO + CoroutineExceptionHandler { _, error -> exception.set(error) },
                    reads, { if (it == "a") fixture.ownerA else fixture.ownerB }, { error("must not send") },
                )
                source = running
                withTimeout(5_000) { running.historyChoices.first { !it.labelsLoading && it.lines.size == 2 && it.lines.all { line -> line.label != null } } }
                val initialStarted = reads.started.get()
                val initialCompleted = reads.completed.get()
                val first = LabelReadRequest()
                reads.next.set(first)
                running.refresh()
                assertEquals(2, withTimeout(5_000) { first.entered.await() }.size)

                val replacement = LabelReadRequest()
                reads.next.set(replacement)
                running.refresh()
                withTimeout(5_000) { first.cancelled.await(); replacement.entered.await() }
                assertTrue(running.historyChoices.value.labelsLoading)
                replacement.release.complete(Unit)
                val choices = withTimeout(5_000) { running.historyChoices.first { !it.labelsLoading } }

                assertFalse(choices.labelsFailed)
                assertEquals(2, choices.lines.size)
                assertEquals(original, choices.lines.single { it.owner == fixture.ownerA }.label)
                assertTrue(choices.lines.all { it.label != null })
                assertEquals(2, reads.started.get() - initialStarted)
                assertEquals(1, reads.completed.get() - initialCompleted)
                assertEquals(1, reads.cancelled.get())
                assertEquals(1L, stored.countUnreadIncoming())
                assertNull(exception.get())
            } finally {
                source?.close()
                stored.close()
            }
        }
    }

    @Test fun activeOwnerArrivingAfterLabelSnapshotGetsOneFollowUpRead() = runBlocking {
        SmsRoomBoundaryFixture().use { fixture ->
            val stored = fixture.open()
            var source: EngineMessageSource? = null
            try {
                stored.insertIncomingIfAbsent(fixture.ownerA, "history", "wire", "155501", "synthetic", 1, 1)
                val original = stored.saveOwnerRemark(fixture.ownerA, MessageOwnerRemark.parse("synthetic remark"))
                val reads = LabelReadBarrier(stored)
                val roster = AtomicReference(listOf("a"))
                val nextRoster = AtomicReference<LabelReadRequest?>(null)
                val exception = AtomicReference<Throwable?>(null)
                val running = EngineMessageSource(
                    EngineMessageRuntime({
                        nextRoster.getAndSet(null)?.let { request ->
                            request.entered.complete(emptyList())
                            request.release.await()
                        }
                        emptyList()
                    }, { error("no live transport") }, { 100 }, { roster.get() }),
                    Dispatchers.IO + CoroutineExceptionHandler { _, error -> exception.set(error) },
                    reads, { if (it == "a") fixture.ownerA else fixture.ownerB }, { error("must not send") },
                )
                source = running
                withTimeout(5_000) { running.historyChoices.first { !it.labelsLoading && it.activeRosterComplete && it.lines.size == 1 } }
                val initialStarted = reads.started.get()
                val oldSnapshot = LabelReadRequest()
                val heldRoster = LabelReadRequest()
                reads.next.set(oldSnapshot)
                nextRoster.set(heldRoster)
                running.refresh()
                assertEquals(listOf(fixture.ownerA), withTimeout(5_000) { oldSnapshot.entered.await() })
                withTimeout(5_000) { heldRoster.entered.await() }

                roster.set(listOf("a", "b"))
                heldRoster.release.complete(Unit)
                val pending = withTimeout(5_000) { running.historyChoices.first { it.lines.size == 2 } }
                assertTrue(pending.labelsLoading)
                assertNull(pending.lines.single { it.owner == fixture.ownerB }.label)
                val followUp = LabelReadRequest()
                reads.next.set(followUp)
                oldSnapshot.release.complete(Unit)
                assertEquals(setOf(fixture.ownerA, fixture.ownerB), withTimeout(5_000) { followUp.entered.await() }.toSet())
                assertTrue(running.historyChoices.value.labelsLoading)
                followUp.release.complete(Unit)
                val choices = withTimeout(5_000) { running.historyChoices.first { !it.labelsLoading } }

                assertFalse(choices.labelsFailed)
                assertEquals(original, choices.lines.single { it.owner == fixture.ownerA }.label)
                assertTrue(choices.lines.all { it.label != null && it.active == true })
                assertEquals(2, choices.lines.map { it.label?.ordinal }.distinct().size)
                assertEquals(2, reads.started.get() - initialStarted)
                assertEquals(0, reads.cancelled.get())
                assertEquals(1L, stored.countUnreadIncoming())
                assertNull(exception.get())
            } finally {
                source?.close()
                stored.close()
            }
        }
    }
}

private class LabelReadRequest {
    val entered = CompletableDeferred<List<SimOwnerId>>()
    val release = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
}

private class LabelReadBarrier(private val stored: MessageRepository) : MessageRepository by stored {
    val next = AtomicReference<LabelReadRequest?>(null)
    val started = AtomicInteger()
    val completed = AtomicInteger()
    val cancelled = AtomicInteger()

    override suspend fun ownerLabels(owners: List<SimOwnerId>): Map<SimOwnerId, MessageOwnerLabel> {
        started.incrementAndGet()
        val request = next.getAndSet(null)
        try {
            val labels = stored.ownerLabels(owners)
            if (request != null) {
                request.entered.complete(owners)
                request.release.await()
            }
            completed.incrementAndGet()
            return labels
        } catch (error: CancellationException) {
            cancelled.incrementAndGet()
            request?.cancelled?.complete(Unit)
            throw error
        }
    }
}
