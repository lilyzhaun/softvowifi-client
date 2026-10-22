package com.vowifi.sw.manager.data

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

/** Only the IO consumer owns this store. New namespace; never adopts or cleans legacy logs. */
internal class DiagnosticFiles(
    private val directory: Path,
    private val maxBytes: Int = 1024 * 1024,
    private val writeBytes: (FileChannel, ByteBuffer) -> Int = { channel, bytes -> channel.write(bytes) },
) : Closeable {
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var channel: FileChannel? = null
    private var size = 0L
    private var needsSeparator = false
    private var closed = false
    @Volatile var failure: DiagnosticReason? = null
        private set

    init {
        try {
            if (!safeAncestors(directory.parent)) fail(DiagnosticReason.UNSAFE_PATH)
            else {
                if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                if (!safeDirectory()) fail(DiagnosticReason.UNSAFE_PATH)
                else {
                    val lockFile = directory.resolve("writer.lock")
                    if (Files.exists(lockFile, NOFOLLOW_LINKS) &&
                        (!Files.isRegularFile(lockFile, NOFOLLOW_LINKS) || Files.size(lockFile) != 0L)) {
                        fail(DiagnosticReason.UNSAFE_PATH)
                    } else {
                        lockChannel = open(lockFile)
                        lock = lockChannel!!.tryLock()
                        if (lock == null) fail(DiagnosticReason.LOCK_BUSY)
                        else if (validateFiles()) openCurrent()
                    }
                }
            }
        } catch (_: OverlappingFileLockException) { fail(DiagnosticReason.LOCK_BUSY) }
        catch (_: AccessDeniedException) { fail(DiagnosticReason.PERMISSION_DENIED) }
        catch (_: IOException) { fail(DiagnosticReason.IO_FAILED) }
        catch (_: SecurityException) { fail(DiagnosticReason.PERMISSION_DENIED) }
    }

    fun append(bytes: ByteArray): Boolean {
        if (failure != null || closed) return false
        if (bytes.isEmpty() || bytes.size > 2048 || bytes.last() != '\n'.code.toByte()) return false
        try {
            if (size + bytes.size + (if (needsSeparator) 1 else 0) > maxBytes) {
                if (!validateFiles()) return false
                try {
                    channel?.close(); channel = null
                    val previous = directory.resolve("app.jsonl.1")
                    if (Files.exists(previous, NOFOLLOW_LINKS)) {
                        Files.move(previous, directory.resolve("app.jsonl.2"), REPLACE_EXISTING)
                    }
                    Files.move(directory.resolve("app.jsonl"), previous, REPLACE_EXISTING)
                    openCurrent()
                } catch (_: IOException) { fail(DiagnosticReason.ROTATION_FAILED); return false }
            }
            // Preserve an interrupted record, but do not concatenate the next process's first event.
            val payload = if (needsSeparator) byteArrayOf('\n'.code.toByte()) + bytes else bytes
            val written = writeBytes(channel!!, ByteBuffer.wrap(payload))
            if (written != payload.size) { fail(DiagnosticReason.IO_FAILED); return false }
            size += written
            needsSeparator = false
            return true // local write only; no fsync, power-loss or receiver acknowledgement
        } catch (_: AccessDeniedException) { fail(DiagnosticReason.PERMISSION_DENIED) }
        catch (_: IOException) { fail(DiagnosticReason.IO_FAILED) }
        catch (_: SecurityException) { fail(DiagnosticReason.PERMISSION_DENIED) }
        return false
    }

    private fun validateFiles(): Boolean {
        if (!safeDirectory()) { fail(DiagnosticReason.UNSAFE_PATH); return false }
        // At most five entries inspected at admission/rotation, never an unbounded directory scan.
        Files.newDirectoryStream(directory).use { entries ->
            var count = 0
            for (path in entries) {
                if (++count > 4 || path.fileName.toString() !in names) {
                    fail(DiagnosticReason.STORAGE_LIMIT); return false
                }
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) { fail(DiagnosticReason.UNSAFE_PATH); return false }
                if (Files.size(path) > if (path.fileName.toString() == "writer.lock") 0 else maxBytes) {
                    fail(DiagnosticReason.STORAGE_LIMIT); return false
                }
            }
        }
        return true
    }

    private fun safeDirectory() = safeAncestors(directory) && Files.isDirectory(directory, NOFOLLOW_LINKS)
    private fun openCurrent() {
        channel = open(directory.resolve("app.jsonl"))
        size = channel!!.size()
        val tail = ByteBuffer.allocate(1)
        needsSeparator = size > 0 && (channel!!.read(tail, size - 1) != 1 || tail.array()[0] != '\n'.code.toByte())
        channel!!.position(size)
    }
    private fun open(path: Path): FileChannel = FileChannel.open(path, setOf(READ, WRITE, CREATE, NOFOLLOW_LINKS),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))

    private fun fail(reason: DiagnosticReason) {
        if (failure == null) failure = reason
        close()
    }

    override fun close() {
        closed = true
        try { channel?.close() } catch (_: IOException) { failure = failure ?: DiagnosticReason.IO_FAILED }
        channel = null
        try { lock?.release() } catch (_: IOException) { failure = failure ?: DiagnosticReason.IO_FAILED }
        lock = null
        try { lockChannel?.close() } catch (_: IOException) { failure = failure ?: DiagnosticReason.IO_FAILED }
        lockChannel = null
    }

    companion object {
        // Resolve only the framework-provided private root, never the managed directory or files.
        fun directoryInAppStorage(filesDir: Path): Path = filesDir.toRealPath().resolve("diagnostics-v1")

        private val names = setOf("app.jsonl", "app.jsonl.1", "app.jsonl.2", "writer.lock")
        private fun safeAncestors(path: Path?): Boolean {
            var current = path?.toAbsolutePath()?.normalize()
            var count = 0
            while (current != null) {
                if (++count > 32 || Files.isSymbolicLink(current)) return false
                current = current.parent
            }
            return true
        }

        fun readWindow(directory: Path): DiagnosticWindow = readWindow { directory }

        fun readWindow(directory: () -> Path?): DiagnosticWindow {
            val lines = ArrayDeque<String>()
            var remaining = 65536
            var truncated = false
            var issue: DiagnosticReason? = null
            try {
                val resolved = directory() ?: return DiagnosticWindow(emptyList(), false, DiagnosticReason.UNAVAILABLE)
                if (!safeAncestors(resolved)) return DiagnosticWindow(emptyList(), false, DiagnosticReason.UNSAFE_PATH)
                for (name in listOf("app.jsonl", "app.jsonl.1", "app.jsonl.2")) {
                    val path = resolved.resolve(name)
                    if (!Files.exists(path, NOFOLLOW_LINKS)) continue
                    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        issue = DiagnosticReason.UNSAFE_PATH; continue
                    }
                    if (remaining == 0 || lines.size >= 50) { truncated = true; break }
                    FileChannel.open(path, READ, NOFOLLOW_LINKS).use { file ->
                        val size = file.size()
                        val amount = size.coerceAtMost(remaining.toLong()).toInt()
                        val bytes = ByteBuffer.allocate(amount)
                        file.position(size - amount)
                        // A regular-file short read is an incomplete window, not a successful full read.
                        val read = if (amount == 0) 0 else file.read(bytes)
                        if (read != amount) issue = DiagnosticReason.IO_FAILED
                        remaining -= amount
                        val content = bytes.array().copyOf(bytes.position()).decodeToString()
                        val complete = if (size > amount) content.substringAfter('\n', "") else content
                        truncated = truncated || size > amount
                        val parts = complete.split('\n').dropLast(1) // incomplete trailing record is never parsed
                        if (complete.isNotEmpty() && !complete.endsWith('\n')) issue = DiagnosticReason.IO_FAILED
                        for (line in parts.asReversed()) {
                            if (lines.size >= 50) { truncated = true; break }
                            lines.addFirst(line)
                        }
                    }
                }
            } catch (_: AccessDeniedException) { issue = DiagnosticReason.PERMISSION_DENIED }
            catch (_: IOException) { issue = DiagnosticReason.IO_FAILED }
            catch (_: SecurityException) { issue = DiagnosticReason.PERMISSION_DENIED }
            return DiagnosticWindow(lines.toList(), truncated, issue)
        }
    }
}

internal data class DiagnosticWindow(val lines: List<String>, val truncated: Boolean, val issue: DiagnosticReason?)
