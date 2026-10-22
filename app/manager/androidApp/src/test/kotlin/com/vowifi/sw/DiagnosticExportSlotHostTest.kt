@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticExportSlotHostTest {
    @Test fun successfulAndFailedCopiesAlwaysReleaseSingleSlot() {
        val root=Files.createTempDirectory("diagnostic-slot-")
        try {
            val slot=DiagnosticExportSlot(root.resolve("export"))
            val output=ByteArrayOutputStream();slot.export(output){it.write("safe".toByteArray())}
            assertEquals("safe",output.toString("UTF-8"))
            assertFalse(Files.exists(root.resolve("export/export.jsonl")))
            try {slot.export(output){it.write("partial".toByteArray());throw IOException("synthetic")};fail("failure hidden") } catch (_:IOException) {}
            assertFalse(Files.exists(root.resolve("export/export.jsonl")))
        } finally {root.toFile().deleteRecursively()}
    }

    @Test fun outputBudgetAndCompetingExportAreRejectedWithoutExtraFiles() {
        val root=Files.createTempDirectory("diagnostic-budget-")
        try {
            val slot=DiagnosticExportSlot(root.resolve("export"),maxBytes=32)
            try {slot.export(ByteArrayOutputStream()){it.write(ByteArray(33))};fail("oversized temporary artifact accepted")} catch (_:IOException) {}
            slot.export(ByteArrayOutputStream()) {
                try {slot.export(ByteArrayOutputStream()){fail("competing export generator entered")};fail("second export acquired slot")} catch (_:IOException) {}
                it.write(byteArrayOf(1))
            }
            assertFalse(Files.exists(root.resolve("export/export.jsonl")))
        } finally {root.toFile().deleteRecursively()}
    }

    @Test fun unownedFilesAndSymlinksAreNeverDeletedOrAdopted() {
        val root=Files.createTempDirectory("diagnostic-unowned-")
        try {
            val dir=Files.createDirectory(root.resolve("export"));val unknown=dir.resolve("keep")
            Files.setPosixFilePermissions(dir,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            Files.writeString(unknown,"preserve")
            try {DiagnosticExportSlot(dir).export(ByteArrayOutputStream()){fail("unsafe generator entered")};fail("unknown file adopted")} catch (_:IOException) {}
            assertEquals("preserve",Files.readString(unknown))
        } finally {root.toFile().deleteRecursively()}
    }

    @Test fun interruptedGenerationAndStartupCleanupLeaveNoPayload() {
        val root=Files.createTempDirectory("diagnostic-cancel-")
        try {
            val slot=DiagnosticExportSlot(root.resolve("export"))
            try {slot.export(ByteArrayOutputStream()){it.write(byteArrayOf(1));throw InterruptedException()};fail("cancel swallowed")} catch (_:InterruptedException) {}
            assertFalse(Files.exists(root.resolve("export/export.jsonl")))
            Files.write(root.resolve("export/export.jsonl"),byteArrayOf(1),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(root.resolve("export/export.jsonl"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            slot.cleanup()
            assertFalse(Files.exists(root.resolve("export/export.jsonl")))
        } finally {root.toFile().deleteRecursively()}
    }

    @Test fun destinationFailureRemovesOnlyOurTemporaryPayload() {
        val root=Files.createTempDirectory("diagnostic-copy-failure-")
        try {
            val slot=DiagnosticExportSlot(root.resolve("export"))
            val failed=object:OutputStream(){override fun write(value:Int){throw IOException("synthetic destination failure")}}
            try {slot.export(failed){it.write(byteArrayOf(1,2,3))};fail("copy failure hidden")} catch (_:IOException) {}
            assertFalse(Files.exists(root.resolve("export/export.jsonl")))
        } finally {root.toFile().deleteRecursively()}
    }
}
