package com.vowifi.sw.manager.model

import kotlinx.serialization.Serializable

@Serializable
data class RegistrationEvent(
    val timestampMs: Long,
    val subId: Int,
    val state: String,
    val transportType: Int? = null,
    val attributeFlags: Int? = null,
    val featureTags: List<String>? = null,
    val reasonCode: Int? = null,
    val extraCode: Int? = null,
    val extraMessage: String? = null,
) {
    val normalizedState: RegistrationState
        get() = RegistrationState.entries.firstOrNull { it.name == state } ?: RegistrationState.UNKNOWN
}

enum class RegistrationState {
    REGISTERED,
    REGISTERING,
    UNREGISTERED,
    UNKNOWN,
}

data class ParseIssue(
    val lineNumber: Int,
    val message: String,
)

data class RegistrationSnapshot(
    val events: List<RegistrationEvent>,
    val latestBySubId: List<RegistrationEvent>,
    val issues: List<ParseIssue>,
)

sealed interface RegistrationUiState {
    data object Loading : RegistrationUiState
    data object Empty : RegistrationUiState
    data class Data(val snapshot: RegistrationSnapshot) : RegistrationUiState
    data class ParseError(val issues: List<ParseIssue>) : RegistrationUiState
    data class ReadError(val message: String) : RegistrationUiState
}
