package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogTail
import com.vowifi.sw.manager.model.OperationalLogUiState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class OperationalLogGroupRead(val tail: OperationalLogTail, val issue: String? = null)
internal data class OperationalLogGroupSelection(val id: String?, val sameGroup: Boolean)

/** Only trusted catalog identities form a group; suffixes and line bodies never establish ownership. */
internal class OperationalLogGroupReader(private val reader: OperationalLogReader) {
    fun selection(files: List<OperationalLogFile>, previous: OperationalLogFile?, selected: String?): OperationalLogGroupSelection {
        val chosen = files.firstOrNull { it.id == selected }
            ?: previous?.groupKey?.let { group -> files.firstOrNull { it.groupKey == group } }
            ?: files.firstOrNull()
        return OperationalLogGroupSelection(chosen?.id, chosen?.id == selected ||
            (previous?.groupKey != null && previous.groupKey == chosen?.groupKey))
    }

    suspend fun read(id: String, legacy: Boolean, files: List<OperationalLogFile>): OperationalLogGroupRead {
        val selected = files.first { it.id == id }
        if (legacy || selected.groupKey == null) return OperationalLogGroupRead(reader.read(id, legacy, selected))
        val members = files.filter { it.groupKey == selected.groupKey }
            .sortedWith(compareBy<OperationalLogFile> { it.name != "engine.log" }.thenBy { it.id })
        val tails = mutableListOf<OperationalLogTail>()
        val failures = mutableListOf<LogReadFailure>()
        for (member in members) {
            currentCoroutineContext().ensureActive()
            try { tails += reader.read(member.id, legacy, member) }
            catch (error: LogReadFailure) { failures += error }
        }
        currentCoroutineContext().ensureActive()
        if (tails.isEmpty()) {
            val failure = failures.firstOrNull { it.state is OperationalLogUiState.PermissionDenied } ?: failures.first()
            throw LogReadFailure(failure.state, missing = failures.all { it.missing },
                unsupported = failures.all { it.unsupported }, blocking = true)
        }
        val entries = tails.flatMap { it.lines }.map { entry ->
            if (knownTime(entry.timestamp)) entry else entry.copy(timestamp = "时间未知")
        }.withIndex().sortedWith(compareBy<IndexedValue<com.vowifi.sw.manager.model.OperationalLogEntry>> {
            it.value.timestamp != "时间未知"
        }.thenBy { it.value.timestamp }.thenBy { it.value.origin.ordinal }.thenBy { it.index }).map { it.value }
        return OperationalLogGroupRead(OperationalLogTail(id, tails.any { it.truncated }, entries, tails.flatMap { it.issues }),
            failures.firstOrNull()?.let { "部分日志读取失败：${it.safeMessage}" })
    }

    private val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withResolverStyle(ResolverStyle.STRICT)
    private fun knownTime(value: String): Boolean = try { LocalDateTime.parse(value, timestamp); true }
    catch (_: DateTimeParseException) { false }
}
