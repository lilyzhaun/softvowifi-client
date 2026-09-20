@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineMessageReadTest {
    @Test
    fun arrivalPublishesDuringReadAwaitAndSurvivesReadMerge() = runTest(UnconfinedTestDispatcher()) {
        val fixture = readFixture()
        try {
            fixture.source.markVisibleRead(fixture.request)
            assertTrue(fixture.repository.started.isCompleted)
            fixture.transport.emitReceived("mt-later", "+155501", "synth-later", 2)
            assertEquals(2, fixture.ready().records.size)
            fixture.repository.release.complete(Unit)
            val records = fixture.ready().records
            assertTrue(assertIs<MessageRecord.Incoming>(records.first()).isRead)
            assertFalse(assertIs<MessageRecord.Incoming>(records.last()).isRead)
            assertEquals(listOf("msg-1", "msg-2"), records.map { it.id })
        } finally {
            fixture.source.close()
        }
    }

    @Test
    fun ownerSwitchDuringReadAwaitRejectsStalePublication() = runTest(UnconfinedTestDispatcher()) {
        val fixture = readFixture()
        try {
            fixture.source.markVisibleRead(fixture.request)
            fixture.iccid = "8986002"
            fixture.source.refresh()
            assertEquals(listOf("owner-b"), fixture.ready().records.map { it.id })
            val switched = fixture.ready()
            fixture.repository.release.complete(Unit)
            assertEquals(switched, fixture.ready())
            assertFalse(assertIs<MessageRecord.Incoming>(fixture.ready().records.single()).isRead)
        } finally {
            fixture.source.close()
        }
    }

    @Test
    fun staleReadFailureDoesNotPublishNoticeToNewOwner() = runTest(UnconfinedTestDispatcher()) {
        val fixture = readFixture()
        fixture.repository.fail = true
        try {
            fixture.source.markVisibleRead(fixture.request)
            fixture.iccid = "8986002"
            fixture.source.refresh()
            assertEquals(listOf("owner-b"), fixture.ready().records.map { it.id })
            fixture.repository.release.complete(Unit)
            assertEquals(null, fixture.ready().attachmentNotice)
            assertTrue(fixture.ready().composerEnabled)
            assertFalse(assertIs<MessageRecord.Incoming>(fixture.ready().records.single()).isRead)
        } finally {
            fixture.source.close()
        }
    }

    @Test
    fun storageFailureAfterAwaitNeverMarksCachedRecordRead() = runTest(UnconfinedTestDispatcher()) {
        val fixture = readFixture()
        fixture.repository.fail = true
        try {
            fixture.source.markVisibleRead(fixture.request)
            fixture.repository.release.complete(Unit)
            assertFalse(assertIs<MessageRecord.Incoming>(fixture.ready().records.single()).isRead)
            assertEquals(STORAGE_SYNC_NOTICE, fixture.ready().attachmentNotice)
        } finally {
            fixture.source.close()
        }
    }
}

private class ReadFixture(
    val repository: AwaitingReadRepository,
    val transport: FakeEngineMessageTransport,
) {
    var iccid = "8986001"
    lateinit var source: EngineMessageSource
    val request = checkNotNull(ObservedIncomingReadRequest.create(OWNER_A, "155501", listOf("msg-1")))
    suspend fun ready(): InspectedMessageInbox = inspectInboxThreads(source)
}

private suspend fun readFixture(): ReadFixture {
    val backing = FakeMessageRepository()
    backing.insertIncomingIfAbsent(OWNER_B, "owner-b", "mt-b", "+155501", "synth-b", 1, 1)
    val fixture = ReadFixture(AwaitingReadRepository(backing), FakeEngineMessageTransport())
    fixture.source = EngineMessageSource(
        runtime = EngineMessageRuntime(
            listInstances = { listOf(EngineInstanceState(fixture.iccid, EngineLifecycle.RUNNING, pid = 7)) },
            transportFor = { fixture.transport },
            clock = { 9000L },
        ),
        parentContext = coroutineContext,
        repository = fixture.repository,
        ownerIdFor = ::ownerFor,
        nextMessageId = sequentialIds(),
    )
    fixture.transport.emitReceived("mt-seen", "+155501", "synth-seen", 1)
    return fixture
}

private class AwaitingReadRepository(private val backing: MessageRepository) : MessageRepository by backing {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var fail = false

    override suspend fun markIncomingRead(request: ObservedIncomingReadRequest, nowMs: Long): IncomingReadUpdate {
        started.complete(Unit)
        release.await()
        if (fail) throw MessageStorageException()
        return backing.markIncomingRead(request, nowMs)
    }
}
