package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ParseIssue
import com.vowifi.sw.manager.model.RegistrationEvent
import com.vowifi.sw.manager.model.RegistrationSnapshot
import com.vowifi.sw.manager.model.RegistrationUiState
import kotlinx.serialization.json.Json

class RegistrationLogParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(content: String): RegistrationUiState {
        if (content.isBlank()) return RegistrationUiState.Empty

        val events = mutableListOf<RegistrationEvent>()
        val issues = mutableListOf<ParseIssue>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            runCatching { json.decodeFromString<RegistrationEvent>(line) }
                .onSuccess(events::add)
                .onFailure { error ->
                    issues += ParseIssue(index + 1, error.message ?: "无法解析 JSON")
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
}
