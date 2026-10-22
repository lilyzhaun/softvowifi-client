@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.DiagnosticFiles
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticFilesHostTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = temporary.root.toPath().resolve("diagnostics-v1")

    @Test fun repeatedRotationAndRestartHaveHardByteAndFileBounds() {
        val dir = directory()
        repeat(5) {
            DiagnosticFiles(dir, maxBytes = 4096).use { store ->
                repeat(50) { assertTrue(store.append(("x".repeat(1000) + "\n").toByteArray())) }
            }
            val files = Files.list(dir).use { it.toList() }
            assertEquals(4, files.size) // three payload files plus zero-byte lock
            assertTrue(files.all { Files.size(it) <= 4096 })
            assertTrue(files.sumOf { Files.size(it) } <= 3 * 4096)
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(dir.resolve("app.jsonl"))))
            println("diagnostic_rotation files=${files.size} payload_bytes=${files.sumOf { Files.size(it) }} cap=${3 * 4096}")
        }
    }

    @Test fun secondWriterCannotRotateOrDeleteFirstWritersFiles() {
        val dir = directory()
        DiagnosticFiles(dir, maxBytes = 4096).use { first ->
            assertTrue(first.append("first\n".toByteArray()))
            DiagnosticFiles(dir, maxBytes = 4096).use { second ->
                assertFalse(second.append("second\n".toByteArray()))
            }
            assertTrue(first.append("last\n".toByteArray()))
        }
        assertEquals("first\nlast\n", Files.readString(dir.resolve("app.jsonl")))
    }

    @Test fun symlinkAndOversizedPreexistingFilesAreNotFollowedOrDeleted() {
        val dir = directory()
        Files.createDirectory(dir)
        val target = temporary.newFile("outside").toPath()
        Files.writeString(target, "keep")
        Files.createSymbolicLink(dir.resolve("app.jsonl"), target)
        DiagnosticFiles(dir, maxBytes = 4096).use { assertFalse(it.append("bad\n".toByteArray())) }
        assertEquals("keep", Files.readString(target))
        Files.delete(dir.resolve("app.jsonl"))
        Files.writeString(dir.resolve("app.jsonl"), "y".repeat(4097))
        DiagnosticFiles(dir, maxBytes = 4096).use { assertFalse(it.append("bad\n".toByteArray())) }
        assertEquals(4097, Files.size(dir.resolve("app.jsonl")))
    }

    @Test fun rotationFailureDisablesWritesAndPreservesExistingEvidence() {
        val dir = directory()
        DiagnosticFiles(dir, maxBytes = 2048).use { store ->
            assertTrue(store.append(("x".repeat(2047) + "\n").toByteArray()))
            Files.createDirectory(dir.resolve("app.jsonl.1"))
            repeat(100) { assertFalse(store.append("next\n".toByteArray())) }
            assertEquals(2048, Files.size(dir.resolve("app.jsonl")))
        }
    }

    @Test fun unreadableDirectoryDoesNotThrowAndReadWindowIsNotExport() {
        val dir = directory()
        Files.writeString(dir, "not a directory")
        DiagnosticFiles(dir).use { assertFalse(it.append("safe\n".toByteArray())) }
        assertEquals("not a directory", Files.readString(dir))
    }

    @Test fun windowReadsRotationsButIsExplicitlyTruncatedAtFiftyRecords() {
        val dir = directory()
        DiagnosticFiles(dir, maxBytes = 2048).use { store ->
            repeat(100) { assertTrue(store.append(("record-$it".padEnd(50, 'x') + "\n").toByteArray())) }
        }
        val window = DiagnosticFiles.readWindow(dir)
        assertEquals(50, window.lines.size)
        assertTrue(window.truncated)
        assertTrue(window.lines.first().startsWith("record-50"))
        assertTrue(window.lines.last().startsWith("record-99"))
        assertNull(window.issue)
    }

    @Test fun parentSymlinksAndUnknownFilesAreNotAdoptedAndClosedStoreDoesNotWrite() {
        val outside = temporary.newFolder("outside-dir").toPath()
        val link = temporary.root.toPath().resolve("link")
        Files.createSymbolicLink(link, outside)
        DiagnosticFiles(link.resolve("diagnostics-v1")).use { assertFalse(it.append("bad\n".toByteArray())) }
        assertFalse(Files.exists(outside.resolve("diagnostics-v1")))
        val dir = directory()
        Files.createDirectory(dir)
        Files.writeString(dir.resolve("legacy.log"), "preserve")
        DiagnosticFiles(dir).use { assertFalse(it.append("bad\n".toByteArray())) }
        assertEquals("preserve", Files.readString(dir.resolve("legacy.log")))
        val normal = DiagnosticFiles(temporary.root.toPath().resolve("another"))
        normal.close()
        assertFalse(normal.append("after-close\n".toByteArray()))
    }

    @Test fun permissionFailureIsTypedAndDoesNotRetry() {
        var attempts = 0
        DiagnosticFiles(directory(), writeBytes = { _, _ ->
            attempts++
            throw java.nio.file.AccessDeniedException("synthetic permission denial")
        }).use { store ->
            repeat(100) { assertFalse(store.append("safe\n".toByteArray())) }
            assertEquals(com.vowifi.sw.manager.data.DiagnosticReason.PERMISSION_DENIED, store.failure)
        }
        assertEquals(1, attempts)
    }

    @Test fun productionDefaultsRemainThreeMebibytesAfterEightMebibytesOfWrites() {
        val dir = directory()
        val bytes = ("x".repeat(2047) + "\n").toByteArray()
        DiagnosticFiles(dir).use { store -> repeat(4096) { assertTrue(store.append(bytes)) } }
        val files = Files.list(dir).use { it.toList() }
        assertEquals(4, files.size)
        assertTrue(files.all { Files.size(it) <= 1024 * 1024 })
        assertEquals(3L * 1024 * 1024, files.sumOf { Files.size(it) })
        println("diagnostic_production_budget files=${files.size} payload_bytes=${files.sumOf { Files.size(it) }} cap=3145728")
    }

    @Test fun restartPreservesTornEvidenceAndSeparatesTheNextRun() {
        val dir = directory()
        Files.createDirectory(dir)
        Files.writeString(dir.resolve("app.jsonl"), "torn")
        DiagnosticFiles(dir, maxBytes = 2048).use { assertTrue(it.append("fresh\n".toByteArray())) }
        assertEquals("torn\nfresh\n", Files.readString(dir.resolve("app.jsonl")))
        Files.writeString(dir.resolve("app.jsonl"), "x".repeat(2048))
        DiagnosticFiles(dir, maxBytes = 2048).use { assertTrue(it.append("next\n".toByteArray())) }
        assertEquals("next\n", Files.readString(dir.resolve("app.jsonl")))
        assertEquals(2048, Files.size(dir.resolve("app.jsonl.1")))
    }

    @Test fun trustedAppStorageAliasIsResolvedButManagedDirectorySymlinkIsStillRejected() {
        val actualBase = temporary.newFolder("app-private").toPath()
        val frameworkAlias = temporary.root.toPath().resolve("framework-storage-alias")
        Files.createSymbolicLink(frameworkAlias, actualBase)
        val diagnosticDirectory = DiagnosticFiles.directoryInAppStorage(frameworkAlias)
        DiagnosticFiles(diagnosticDirectory).use { assertTrue(it.append("observed\n".toByteArray())) }
        assertEquals("observed\n", Files.readString(actualBase.resolve("diagnostics-v1/app.jsonl")))
        assertEquals(listOf("observed"), DiagnosticFiles.readWindow {
            DiagnosticFiles.directoryInAppStorage(frameworkAlias)
        }.lines)

        val otherBase = temporary.newFolder("other-app-private").toPath()
        val outside = temporary.newFolder("not-managed").toPath()
        Files.createSymbolicLink(otherBase.resolve("diagnostics-v1"), outside)
        DiagnosticFiles(DiagnosticFiles.directoryInAppStorage(otherBase)).use {
            assertFalse(it.append("must-not-follow\n".toByteArray()))
        }
        assertFalse(Files.exists(outside.resolve("app.jsonl")))
        assertEquals(com.vowifi.sw.manager.data.DiagnosticReason.UNSAFE_PATH, DiagnosticFiles.readWindow {
            DiagnosticFiles.directoryInAppStorage(otherBase)
        }.issue)
    }

    @Test fun unavailableTrustedRootIsAReadFailureNotAnEmptySuccessfulWindow() {
        val window = DiagnosticFiles.readWindow {
            DiagnosticFiles.directoryInAppStorage(temporary.root.toPath().resolve("missing-private-root"))
        }
        assertEquals(com.vowifi.sw.manager.data.DiagnosticReason.IO_FAILED, window.issue)
        assertTrue(window.lines.isEmpty())
        assertEquals(com.vowifi.sw.manager.data.DiagnosticReason.UNAVAILABLE,
            DiagnosticFiles.readWindow { null }.issue)
    }
}
