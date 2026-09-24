package com.vowifi.sw.manager.data

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagePagingRequestedExtentTest {
    @Test fun repeatedRefreshKeepsAcceptedExtentForTheSameOwnerAndQuery() = runTest {
        // Given: an accepted cursor request in a nonempty search is still pending.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed()
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            fixture.assertFirstPage(source)
            fixture.releaseRecovery.complete(Unit)
            fixture.releaseRefresh.complete(Unit)
            runCurrent()
            source.searchHistory("SYNTHETIC")
            runCurrent()
            source.loadMoreHistory()
            runCurrent()
            assertTrue(fixture.appendEntered.isCompleted)

            // When: two independent refreshes supersede the pending request.
            source.refresh()
            runCurrent()
            fixture.observe(source, "first-refresh")
            source.refresh()
            runCurrent()
            fixture.observe(source, "second-refresh")
            fixture.releaseAppend.complete(Unit)
            runCurrent()

            // Then: the accepted extent is retained without growing on refresh.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading)
            assertFalse(final.page.failed)
            assertEquals(100, final.page.items.size, fixture.evidence())
        } finally { fixture.close(source); runCurrent() }
    }

    @Test fun changedQueryResetsPendingExtentToOnePage() = runTest {
        // Given: an accepted append belongs to the original query.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed()
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            fixture.releaseRecovery.complete(Unit)
            fixture.releaseRefresh.complete(Unit)
            runCurrent()
            source.loadMoreHistory()
            runCurrent()
            assertTrue(fixture.appendEntered.isCompleted)

            // When: search replaces that query before the cursor result returns.
            source.searchHistory("SYNTHETIC")
            runCurrent()
            fixture.releaseAppend.complete(Unit)
            runCurrent()
            source.refresh()
            runCurrent()

            // Then: refresh does not inherit the previous query's accepted extent.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading)
            assertFalse(final.page.failed)
            assertTrue(final.page.query == "SYNTHETIC")
            assertEquals(50, final.page.items.size, fixture.evidence())
        } finally { fixture.close(source); runCurrent() }
    }

    @Test fun changedOwnerResetsPendingExtentToOnePage() = runTest {
        // Given: the current owner's accepted append is suspended.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed(includeOtherOwner = true)
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            fixture.releaseRecovery.complete(Unit)
            fixture.releaseRefresh.complete(Unit)
            runCurrent()
            source.loadMoreHistory()
            runCurrent()
            assertTrue(fixture.appendEntered.isCompleted)

            // When: the user selects another persisted owner with multiple pages.
            source.selectHistoryOwner(fixture.otherOwner)
            runCurrent()
            fixture.releaseAppend.complete(Unit)
            runCurrent()
            source.refresh()
            runCurrent()

            // Then: the new owner starts at one page, including subsequent refresh.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading)
            assertFalse(final.page.failed)
            assertTrue(final.ownerId == fixture.otherOwner)
            assertEquals(50, final.page.items.size, fixture.evidence())
        } finally { fixture.close(source); runCurrent() }
    }

    @Test fun failedAppendRetriesDoNotInflateTheRefreshedExtent() = runTest {
        // Given: the first append and its first explicit retry fail at storage.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed()
        fixture.cursorFailuresRemaining = 2
        fixture.releaseRecovery.complete(Unit)
        fixture.releaseRefresh.complete(Unit)
        fixture.releaseAppend.complete(Unit)
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            source.loadMoreHistory()
            runCurrent()
            val firstFailure = fixture.observe(source, "first-failure")
            assertTrue(firstFailure.page.failed)
            assertEquals(50, firstFailure.page.items.size)
            source.loadMoreHistory()
            runCurrent()
            val retryFailure = fixture.observe(source, "retry-failure")
            assertTrue(retryFailure.page.failed)
            assertEquals(50, retryFailure.page.items.size)

            // When: the next explicit retry succeeds and the source refreshes.
            source.loadMoreHistory()
            runCurrent()
            assertEquals(100, fixture.observe(source, "retry-finished").page.items.size)
            source.refresh()
            runCurrent()

            // Then: retries still represent the same second page, not extra demand.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading)
            assertFalse(final.page.failed)
            assertEquals(2, fixture.failedCursorRequests)
            assertEquals(100, final.page.items.size, fixture.evidence())
        } finally { fixture.close(source); runCurrent() }
    }

    @Test fun underfilledLastPageStopsAtTheRealEndAcrossRefresh() = runTest {
        // Given: all 122 conversations have been loaded through three pages.
        val fixture = MessagePagingStartupRaceFixture()
        fixture.seed()
        fixture.releaseRecovery.complete(Unit)
        fixture.releaseRefresh.complete(Unit)
        fixture.releaseAppend.complete(Unit)
        val source = fixture.open(coroutineContext)
        try {
            runCurrent()
            source.loadMoreHistory()
            runCurrent()
            source.loadMoreHistory()
            runCurrent()
            val end = fixture.observe(source, "end")
            assertEquals(122, end.page.items.size)
            assertNull(end.page.next)
            val cursorRequests = fixture.cursorRequests

            // When: load-more is attempted at the end, followed by a refresh.
            source.loadMoreHistory()
            runCurrent()
            assertEquals(cursorRequests, fixture.cursorRequests)
            source.refresh()
            runCurrent()

            // Then: refresh terminates at the real end without inventing 150 rows.
            val final = fixture.observe(source, "final")
            assertFalse(final.page.loading)
            assertFalse(final.page.failed)
            assertNull(final.page.next)
            assertEquals(122, final.page.items.size, fixture.evidence())
        } finally { fixture.close(source); runCurrent() }
    }
}
