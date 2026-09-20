@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.data.LogCommand
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OperationalLogLatestReadTest {
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)
    private val third = "c".repeat(64)

    @Test fun cancelledSuccessCannotOverwriteLatestSelectionAndFiltersStayLocal() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.next("catalog").reply(catalog())
            val old = fixture.next("read $first")
            fixture.source.selectFile(second)
            fixture.source.selectFile(third)
            fixture.source.setLevelFilter(OperationalLogLevel.ERROR)
            assertEquals(third, fixture.ready().selectedFileId)
            assertTrue(fixture.ready().entries.isEmpty())
            assertTrue(fixture.ready().loadInfo.reading)
            old.reply(tail(first))
            fixture.next("read $third").reply(tail(third))
            val done = fixture.finished()
            assertEquals(third, done.selectedFileId)
            assertEquals(OperationalLogLevel.ERROR, done.filter.level)
            assertEquals(1, done.entries.size)
            assertEquals(0, done.filteredEntries.size)
            assertEquals(3, fixture.count)
        } finally { fixture.close() }
    }

    @Test fun cancelledFailureCannotEraseLatestSelection() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.next("catalog").reply(catalog())
            val old = fixture.next("read $first")
            fixture.source.selectFile(second)
            old.reply("""{"ok":false,"code":"PERMISSION_DENIED","message":"denied"}""", 1)
            fixture.next("read $second").reply(tail(second))
            assertEquals(second, fixture.finished().selectedFileId)
        } finally { fixture.close() }
    }

    @Test fun cacheIsLabelledHotSwitchDoesNoIoAndRefreshInvalidates() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.next("catalog").reply(catalog())
            fixture.next("read $first").reply(tail(first))
            fixture.finished()
            fixture.source.selectFile(second)
            fixture.next("read $second").reply(tail(second))
            fixture.finished()
            val count = fixture.count
            fixture.source.selectFile(first)
            assertTrue(fixture.ready().loadInfo.cached)
            assertFalse(fixture.ready().loadInfo.reading)
            fixture.source.selectFile(first)
            assertEquals(count, fixture.count)
            fixture.source.refresh()
            assertFalse(fixture.ready().loadInfo.cached)
            fixture.next("catalog").reply(catalog())
            fixture.next("read $first").reply(tail(first))
            assertFalse(fixture.finished().loadInfo.cached)
        } finally { fixture.close() }
    }

    @Test fun expiredSnapshotStaysVisibleUntilReadCompletes() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.next("catalog").reply(catalog())
            fixture.next("read $first").reply(tail(first))
            fixture.finished()
            fixture.source.selectFile(second)
            fixture.next("read $second").reply(tail(second))
            fixture.finished()
            fixture.now = 5001
            fixture.source.selectFile(first)
            assertTrue(fixture.ready().loadInfo.cached && fixture.ready().loadInfo.reading)
            assertEquals(1, fixture.ready().entries.size)
            fixture.next("read $first").reply(tail(first))
            assertFalse(fixture.finished().loadInfo.cached)
        } finally { fixture.close() }
    }

    @Test fun missingKeyRelistsExactlyOnceAndKeepsPickerAfterSecondFailure() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.next("catalog").reply(catalog())
            fixture.next("read $first").reply(missing(), 14)
            fixture.next("catalog").reply(catalog())
            fixture.next("read $first").reply(missing(), 14)
            val state = fixture.finished()
            assertNotNull(state.loadInfo.issue)
            assertEquals(3, state.files.size)
            assertEquals(4, fixture.count)
        } finally { fixture.close() }
    }

    @Test fun staleSnapshotSurvivesReadFailureForItsOwnSelectedIdentity() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.next("catalog").reply(catalog())
            fixture.next("read $first").reply(tail(first))
            fixture.finished()
            fixture.source.selectFile(second)
            fixture.next("read $second").reply(tail(second))
            fixture.finished()
            fixture.now = 5001
            fixture.source.selectFile(first)
            fixture.next("read $first").reply("broken response")
            val state = withTimeout(3000) {
                fixture.source.state.first { it is OperationalLogUiState.Ready && it.loadInfo.issue != null } as OperationalLogUiState.Ready
            }
            assertEquals(first, state.selectedFileId)
            assertTrue(state.loadInfo.cached)
            assertEquals(1, state.entries.size)
            assertNotNull(state.loadInfo.issue)
        } finally { fixture.close() }
    }

    @Test fun closeDoesNotPublishLateResult() = runBlocking {
        val fixture = Fixture()
        fixture.next("catalog").reply(catalog())
        val pending = fixture.next("read $first")
        fixture.source.close()
        val before = fixture.source.state.value
        pending.reply(tail(first))
        withTimeout(3000) { fixture.job.join() }
        assertEquals(before, fixture.source.state.value)
        assertEquals(2, fixture.count)
    }

    private fun missing() = """{"ok":false,"code":"NOT_FOUND","message":"missing"}"""
    private fun catalog() = """{"ok":true,"rotatedCount":40,"files":[${listOf(first, second, third).joinToString(",") {
        """{"key":"$it","name":"engine.log","suffix":"0001"}"""
    }}]}"""
    private fun tail(key: String) = """{"ok":true,"key":"$key","truncated":false,"lines":["2026-09-11 12:00:00.000 [INFO] [ims] [0001] safe event"]}"""

    private class Pending(val command: String) {
        val response = CompletableDeferred<AndroidSuResult>()
        fun reply(output: String, exit: Int = 0) { response.complete(AndroidSuResult(false, exit, output, false)) }
    }
    private class Fixture {
        val job = SupervisorJob()
        private val queue = Channel<Pending>(Channel.UNLIMITED)
        @Volatile var count = 0
        var now = 0L
        val source = AndroidOperationalLogSource(LogCommand {
            count++
            val pending = Pending(it)
            queue.send(pending)
            withContext(NonCancellable) { pending.response.await() }
        }, CoroutineScope(job + Dispatchers.Default), { now })
        suspend fun next(suffix: String): Pending = withTimeout(3000) {
            queue.receive().also { assertTrue("unexpected command", it.command.endsWith(suffix)) }
        }
        fun ready() = source.state.value as OperationalLogUiState.Ready
        suspend fun finished(): OperationalLogUiState.Ready = withTimeout(3000) {
            source.state.first { it is OperationalLogUiState.Ready && !it.loadInfo.reading } as OperationalLogUiState.Ready
        }
        fun close() { source.close() }
    }
}
