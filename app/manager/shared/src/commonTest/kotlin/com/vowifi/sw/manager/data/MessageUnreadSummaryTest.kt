@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageUnreadSummary
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class MessageUnreadSummaryTest {
    @Test
    fun coldSourceCountsUnattachedOwnerWithoutMixingLoadedHistoryOrNotifications() = runTest {
        val repository = seededUnreadRepository()
        val snapshots = mutableListOf<SimOwnerId>()
        val source = unreadSource(repository, this, observer = MessagePersistenceObserver { owner, _, _ -> snapshots += owner })
        try {
            assertEquals(MessageUnreadSummary.Loading, source.unreadSummary.value)
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(5), source.unreadSummary.value)
            assertEquals(listOf("a-1", "a-2"), inspectInboxThreads(source).records.map { it.id })
            assertEquals(listOf(OWNER_A), snapshots)
        } finally { source.close() }
    }

    @Test
    fun noEngineAndNoLastOwnerStillCountsAllDurableHistory() = runTest {
        val repository = seededUnreadRepository()
        val source = unreadSource(repository, this, instances = emptyList())
        try {
            runCurrent()
            val ready = assertIs<MessageUiState.Inbox>(source.state.value)
            assertFalse(ready.composerEnabled)
            assertEquals(2, source.historyChoices.value.lines.size)
            assertEquals(2L, ready.page.items.sumOf { it.messageCount })
            assertEquals(MessageUnreadSummary.Ready(5), source.unreadSummary.value)
            assertEquals(0, repository.listByOwnerCalls)
        } finally { source.close() }
    }

    @Test
    fun detachedReadOnlyHistoryKeepsAllOwnerTotal() = runTest {
        val repository = seededUnreadRepository()
        repository.setLastOwner(OWNER_A)
        val source = unreadSource(repository, this, instances = emptyList())
        try {
            runCurrent()
            val ready = assertIs<MessageUiState.Inbox>(source.state.value)
            assertFalse(ready.composerEnabled)
            assertEquals(2L, ready.page.items.sumOf { it.messageCount })
            assertEquals(MessageUnreadSummary.Ready(5), source.unreadSummary.value)
        } finally { source.close() }
    }

    @Test
    fun observedReadCommitsFiveToFourAndDuplicateDoesNotAlertOrDrift() = runTest(UnconfinedTestDispatcher()) {
        val repository = seededUnreadRepository()
        val transport = FakeEngineMessageTransport()
        val alerts = mutableListOf<String>()
        val source = unreadSource(repository, this, transport, observer = MessagePersistenceObserver { _, _, fresh ->
            if (fresh != null) alerts += fresh
        })
        try {
            source.markVisibleRead(unreadRequest(OWNER_A, "a-1"))
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(4), source.unreadSummary.value)
            transport.emitReceived("engine-a-1", "+155501", "synthetic-duplicate", 1)
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(4), source.unreadSummary.value)
            assertTrue(alerts.isEmpty())
            assertTrue(assertIs<MessageRecord.Incoming>(repository.listByOwner(OWNER_A).first()).isRead)
            assertEquals(3, repository.listByOwner(OWNER_B).count { it is MessageRecord.Incoming && !it.isRead })
            transport.emitReceived("engine-fresh", "+155501", "synthetic-fresh", 2)
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(5), source.unreadSummary.value)
            assertEquals(1, alerts.size)
        } finally { source.close() }
    }

    @Test
    fun readForUnloadedOwnerUpdatesGlobalCountButNotSelectedList() = runTest(UnconfinedTestDispatcher()) {
        val source = unreadSource(seededUnreadRepository(), this)
        try {
            val before = source.state.value
            source.markVisibleRead(unreadRequest(OWNER_B, "b-1"))
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(4), source.unreadSummary.value)
            assertEquals(before, source.state.value)
        } finally { source.close() }
    }

    @Test
    fun duplicateIngressRecountsDurableStoreEvenWhenNoNewRecordOrAlertIsProduced() = runTest {
        val repository = seededUnreadRepository()
        val transport = FakeEngineMessageTransport()
        val alerts = mutableListOf<String>()
        val source = unreadSource(repository, this, transport, observer = MessagePersistenceObserver { _, _, fresh ->
            if (fresh != null) alerts += fresh
        })
        try {
            runCurrent()
            repository.insertIncomingIfAbsent(OWNER_B, "b-later", "engine-b-later", "+155501", "synthetic", 10, 10)
            transport.emitReceived("engine-a-1", "+155501", "synthetic-duplicate", 1)
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(6), source.unreadSummary.value)
            assertTrue(alerts.isEmpty())
        } finally { source.close() }
    }

    @Test
    fun committedIncomingRecountsEvenIfSelectedHistoryReadBackFails() = runTest {
        val repository = seededUnreadRepository()
        val transport = FakeEngineMessageTransport()
        val source = unreadSource(repository, this, transport)
        try {
            runCurrent()
            repository.failNextList = true
            transport.emitReceived("engine-new", "+155501", "synthetic", 10)
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(6), source.unreadSummary.value)
            assertEquals(2L, assertIs<MessageUiState.Inbox>(source.state.value).page.items.sumOf { it.messageCount })
        } finally { source.close() }
    }

    @Test
    fun storageFailureRetainsLastCountAndRefreshRecoversInsteadOfFalseZero() = runTest(UnconfinedTestDispatcher()) {
        val repository = seededUnreadRepository()
        val source = unreadSource(repository, this)
        try {
            repository.failNextUnreadCount = true
            source.markVisibleRead(unreadRequest(OWNER_A, "a-1"))
            runCurrent()
            assertEquals(MessageUnreadSummary.Unavailable(5), source.unreadSummary.value)
            assertTrue(assertIs<MessageRecord.Incoming>(inspectInboxThreads(source).records.first()).isRead)
            source.refresh()
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(4), source.unreadSummary.value)
        } finally { source.close() }
    }

    @Test
    fun failedInitialCountIsUnknownRatherThanKnownZero() = runTest {
        val repository = FakeMessageRepository().also { it.failNextUnreadCount = true }
        val source = unreadSource(repository, this, instances = emptyList())
        try {
            runCurrent()
            assertEquals(MessageUnreadSummary.Unavailable(null), source.unreadSummary.value)
            source.refresh()
            runCurrent()
            assertEquals(MessageUnreadSummary.Ready(0), source.unreadSummary.value)
        } finally { source.close() }
    }

    @Test
    fun outgoingDeliveryNeverAddsToDurableUnreadTotal() = runTest {
        val repository = seededUnreadRepository()
        repository.insertQueued(OWNER_A, "outgoing", "+155501", "synthetic", 1)
        repository.bindAccepted(OWNER_A, "outgoing", "engine-outgoing", 2)
        repository.applyOutgoingStatus(OWNER_A, "engine-outgoing", SmsEngineStatus.DELIVERED, 3)
        assertEquals(5L, repository.countUnreadIncoming())
    }
}

internal suspend fun seededUnreadRepository(): FakeMessageRepository = FakeMessageRepository().also { repository ->
    for ((owner, prefix, count) in listOf(Triple(OWNER_A, "a", 2), Triple(OWNER_B, "b", 3))) {
        for (index in 1..count) repository.insertIncomingIfAbsent(
            owner, "$prefix-$index", "engine-$prefix-$index", "+155501", "synthetic", index.toLong(), index.toLong(),
        )
    }
}

internal fun unreadRequest(owner: SimOwnerId, id: String): ObservedIncomingReadRequest =
    checkNotNull(ObservedIncomingReadRequest.create(owner, "155501", listOf(id)))

internal fun unreadSource(
    repository: MessageRepository,
    scope: CoroutineScope,
    transport: FakeEngineMessageTransport = FakeEngineMessageTransport(),
    instances: List<EngineInstanceState> = listOf(EngineInstanceState("8986001", EngineLifecycle.RUNNING, pid = 7)),
    observer: MessagePersistenceObserver = MessagePersistenceObserver { _, _, _ -> },
): EngineMessageSource = EngineMessageSource(
    EngineMessageRuntime(listInstances = { instances }, transportFor = { transport }, clock = { 9000L }),
    scope.coroutineContext, repository, ::ownerFor, sequentialIds("summary"), observer,
)
