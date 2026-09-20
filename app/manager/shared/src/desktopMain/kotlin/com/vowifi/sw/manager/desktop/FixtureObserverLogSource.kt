package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.ObserverLogSource
import com.vowifi.sw.manager.data.RegistrationLogParser
import com.vowifi.sw.manager.model.ParseIssue
import com.vowifi.sw.manager.model.RegistrationUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FixtureObserverLogSource(mode: String) : ObserverLogSource {
    private val mutableState = MutableStateFlow(fixture(mode))
    override val state: StateFlow<RegistrationUiState> = mutableState.asStateFlow()

    override fun refresh() {
        mutableState.value = fixture("data")
    }

    private fun fixture(mode: String): RegistrationUiState = when (mode) {
        "loading" -> RegistrationUiState.Loading
        "empty" -> RegistrationUiState.Empty
        "error" -> RegistrationUiState.ParseError(listOf(ParseIssue(1, "样例坏行")))
        "read-error" -> RegistrationUiState.ReadError("样例文件不可读")
        "warning" -> RegistrationLogParser().parse("$DATA\n{broken")
        "registering" -> RegistrationLogParser().parse(REGISTERING)
        "unknown" -> RegistrationLogParser().parse(UNKNOWN)
        else -> RegistrationLogParser().parse(DATA)
    }

    private companion object {
        const val DATA = """{"timestampMs":1786774816368,"subId":1,"state":"REGISTERED","transportType":2,"attributeFlags":0,"featureTags":[]}
{"timestampMs":1786774817000,"subId":2,"state":"UNREGISTERED","reasonCode":1000,"extraCode":999,"extraMessage":"General_Error17-Unable to connect over the selected WLAN network"}"""
        const val REGISTERING = """{"timestampMs":1786774818000,"subId":1,"state":"REGISTERING","transportType":2,"attributeFlags":0,"featureTags":[]}"""
        const val UNKNOWN = """{"timestampMs":1786774819000,"subId":9,"state":"VENDOR_STATE","transportType":77,"attributeFlags":4,"featureTags":["urn:example:very-long-vendor-feature-tag"]}"""
    }
}
