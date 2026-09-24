package com.vowifi.sw

import com.vowifi.sw.manager.data.ObserverLogTail
import com.vowifi.sw.manager.data.RegistrationLogParser
import com.vowifi.sw.manager.model.RegistrationUiState
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObserverLogTailTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sparsePrefixIsNeverReadAndOnlyLatest50ReachParser() {
        val file = temporary.newFile()
        val prefixSize = 64L * 1024 * 1024
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(prefixSize)
            output.seek(prefixSize)
            output.write(("\n" + (1..100).joinToString("\n") { event(it) } + "\n").toByteArray())
        }
        CountingFile(file).use { input ->
            val data = parse(ObserverLogTail.read(input))
            assertEquals((51L..100L).toList(), data.snapshot.events.map { it.timestampMs })
            assertTrue(data.snapshot.issues.isEmpty())
            assertTrue("must not read sparse prefix", input.firstPosition >= prefixSize)
            assertTrue("read bytes must stay within64KiB", input.bytesRead <= 65536)
        }
    }

    @Test fun byteBudgetDropsOnlyPartialOldestRowAndKeepsNewestEnd() {
        val file = temporary.newFile()
        file.writeText((1..100).joinToString("\n") {
            """{"timestampMs":$it,"subId":555,"state":"UNREGISTERED","extraMessage":"${"x".repeat(2000)}"}"""
        })
        CountingFile(file).use { input ->
            val data = parse(ObserverLogTail.read(input))
            assertEquals(65536, input.bytesRead)
            assertTrue(data.snapshot.events.size in 1..50)
            assertEquals(100L, data.snapshot.events.last().timestampMs)
            assertTrue(data.snapshot.issues.isEmpty())
        }
    }

    @Test fun completeJoinedTailWithoutNewlineIsRecoveredButTruncationIsNotHidden() {
        val file = temporary.newFile()
        file.writeText(event(1) + event(2))
        assertEquals(2, parse(ObserverLogTail.read(file)).snapshot.events.size)
        file.appendText("\n{\"timestampMs\":")
        assertEquals(1, parse(ObserverLogTail.read(file)).snapshot.issues.size)
    }

    @Test fun oversizedSingleRecordFailsWithinByteBudget() {
        val file = temporary.newFile()
        file.writeText("{" + "x".repeat(100000))
        CountingFile(file).use { input ->
            assertThrows(IOException::class.java) { ObserverLogTail.read(input) }
            assertEquals(65536, input.bytesRead)
        }
    }

    @Test fun emptySmallCrLfAndBlankRowsKeepOriginalSemantics() {
        val file = temporary.newFile()
        assertEquals("", ObserverLogTail.read(file))
        val text = event(1) + "\r\n\n \t\r\n" + event(2)
        file.writeText(text)
        assertEquals(text, ObserverLogTail.read(file))
        assertEquals(listOf(1L, 2L), parse(ObserverLogTail.read(file)).snapshot.events.map { it.timestampMs })
    }

    private fun parse(content: String) = RegistrationLogParser().parse(content) as RegistrationUiState.Data
    private fun event(timestamp: Int) = """{"timestampMs":$timestamp,"subId":555,"state":"UNREGISTERED"}"""

    private class CountingFile(file: File) : RandomAccessFile(file, "r") {
        var bytesRead = 0
        var firstPosition = Long.MAX_VALUE
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            firstPosition = minOf(firstPosition, filePointer)
            val count = super.read(buffer, offset, length)
            if (count > 0) bytesRead += count
            return count
        }
    }
}
