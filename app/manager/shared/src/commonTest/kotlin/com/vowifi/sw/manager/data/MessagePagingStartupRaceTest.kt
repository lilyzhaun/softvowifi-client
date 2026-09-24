package com.vowifi.sw.manager.data

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagePagingStartupRaceTest {
    @Test fun acceptedAppendReaches100WhenStartupRecoveryFinishesDuringItsRequest() = runTest {
        // Given: the initial page is ready while real source startup recovery is suspended.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed()
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            assertTrue(fixture.recoveryEntered.isCompleted)
            fixture.assertFirstPage(source)

            // When: a cursor request is accepted before startup recovery completes.
            source.loadMoreHistory()
            runCurrent()
            assertTrue(fixture.appendEntered.isCompleted)
            assertTrue(fixture.observe(source, "append-entered").page.loading)
            fixture.releaseRecovery.complete(Unit)
            runCurrent()
            assertTrue(fixture.refreshEntered.isCompleted)
            fixture.observe(source, "startup-refresh-entered")
            fixture.releaseAppend.complete(Unit)
            fixture.releaseRefresh.complete(Unit)
            runCurrent()

            // Then: the accepted user request must not disappear behind a refresh.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading, fixture.evidence())
            assertFalse(final.page.failed, fixture.evidence())
            assertEquals(100, final.page.items.size, fixture.evidence())
        } finally {
            fixture.close(source)
            runCurrent()
        }
    }

    @Test fun appendReaches100WhenStartupRecoveryFinishesBeforeItIsAccepted() = runTest {
        // Given: identical data and source, with startup recovery completed first.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed()
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            fixture.assertFirstPage(source)
            fixture.releaseRecovery.complete(Unit)
            fixture.releaseRefresh.complete(Unit)
            runCurrent()
            assertFalse(fixture.observe(source, "startup-finished").page.loading)

            // When: the same public pagination action is accepted after recovery.
            source.loadMoreHistory()
            runCurrent()
            assertTrue(fixture.appendEntered.isCompleted)
            fixture.releaseAppend.complete(Unit)
            runCurrent()

            // Then: the same repository returns and publishes the next 50 rows.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading)
            assertEquals(100, final.page.items.size, fixture.evidence())
            assertEquals(1, fixture.cursorRequests)
            assertEquals(0, fixture.cancelledCursorRequests)
        } finally {
            fixture.close(source)
            runCurrent()
        }
    }

    @Test fun completedAppendKeeps100WhenStartupRecoveryFinishesAfterItsResult() = runTest {
        // Given: pagination has already published 100 rows before recovery returns.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed()
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            fixture.assertFirstPage(source)
            source.loadMoreHistory()
            fixture.releaseAppend.complete(Unit)
            runCurrent()
            assertEquals(100, fixture.observe(source, "append-finished").page.items.size)

            // When: startup recovery triggers the same real refresh path.
            fixture.releaseRecovery.complete(Unit)
            fixture.releaseRefresh.complete(Unit)
            runCurrent()

            // Then: refresh preserves the completed window instead of shrinking it.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading)
            assertEquals(100, final.page.items.size, fixture.evidence())
            assertEquals(0, fixture.cancelledCursorRequests)
        } finally {
            fixture.close(source)
            runCurrent()
        }
    }

    @Test fun busyRefreshRejectsPaginationBeforeAnyCursorRequestIsAccepted() = runTest {
        // Given: startup refresh is already loading when the user acts.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed()
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            fixture.assertFirstPage(source)
            fixture.releaseRecovery.complete(Unit)
            runCurrent()
            assertTrue(fixture.refreshEntered.isCompleted)
            assertTrue(fixture.observe(source, "refresh-busy").page.loading)

            // When: a load-more action arrives while the page is busy.
            source.loadMoreHistory()
            runCurrent()
            fixture.releaseRefresh.complete(Unit)
            runCurrent()

            // Then: this is rejection, not cancellation of an accepted cursor request.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading)
            assertEquals(50, final.page.items.size)
            assertFalse(fixture.appendEntered.isCompleted)
            assertEquals(0, fixture.cursorRequests, fixture.evidence())
            assertEquals(0, fixture.cancelledCursorRequests)
        } finally {
            fixture.close(source)
            runCurrent()
        }
    }
}
