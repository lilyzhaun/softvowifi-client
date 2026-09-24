package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ParseIssue
import com.vowifi.sw.manager.model.RegistrationEvent
import com.vowifi.sw.manager.model.RegistrationSnapshot
import com.vowifi.sw.manager.model.RegistrationUiState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class RegistrationLogParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(content: String): RegistrationUiState {
        if (content.isBlank()) return RegistrationUiState.Empty

        val events = mutableListOf<RegistrationEvent>()
        val issues = mutableListOf<ParseIssue>()
        val records = ArrayDeque<Pair<Int, String>>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            frames(line).forEach { frame ->
                records.addLast(index + 1 to frame)
                if (records.size > 50) records.removeFirst()
            }
        }
        records.forEach { (lineNumber, frame) ->
            try {
                events += json.decodeFromString<RegistrationEvent>(frame)
            } catch (_: SerializationException) {
                issues += ParseIssue(lineNumber, "IMS 观测记录格式无效")
            }
        }

        if (events.isEmpty()) {
            return if (issues.isEmpty()) RegistrationUiState.Empty else RegistrationUiState.ParseError(issues)
        }

        val latest = events
            .groupBy(RegistrationEvent::subId)
            .values
            .map { subscriptions -> subscriptions.maxBy(RegistrationEvent::timestampMs) }
            .sortedBy(RegistrationEvent::subId)
        return RegistrationUiState.Data(RegistrationSnapshot(events, latest, issues))
    }

    private fun frames(line: String): Sequence<String> = sequence {
        var start = 0
        while (start < line.length) {
            if (line[start].isWhitespace()) {
                start++
                continue
            }
            if (line[start] != '{') {
                yield(line.substring(start))
                return@sequence
            }
            var depth = 0
            var quoted = false
            var escaped = false
            var end = start
            while (end < line.length) {
                val character = line[end++]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                } else {
                    when (character) {
                        '"' -> quoted = true
                        '{' -> depth++
                        '}' -> if (--depth == 0) break
                    }
                }
            }
            yield(line.substring(start, end))
            start = end
        }
    }
}
