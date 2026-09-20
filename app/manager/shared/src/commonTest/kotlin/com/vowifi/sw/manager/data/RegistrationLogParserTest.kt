package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.RegistrationState
import com.vowifi.sw.manager.model.RegistrationUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegistrationLogParserTest {
    private val parser = RegistrationLogParser()

    @Test
    fun keepsLatestEventPerSubscription() {
        val state = parser.parse(
            """{"timestampMs":1,"subId":1,"state":"REGISTERING","transportType":2,"featureTags":[]}
{"timestampMs":3,"subId":1,"state":"REGISTERED","transportType":2,"featureTags":[]}
{"timestampMs":2,"subId":2,"state":"UNREGISTERED","reasonCode":1000}""",
        )
        val data = assertIs<RegistrationUiState.Data>(state)
        assertEquals(2, data.snapshot.latestBySubId.size)
        assertEquals(RegistrationState.REGISTERED, data.snapshot.latestBySubId[0].normalizedState)
    }

    @Test
    fun keepsValidEventsWhenOneLineIsBroken() {
        val state = parser.parse("""{"timestampMs":1,"subId":1,"state":"REGISTERED","unknown":true}
{broken""")
        val data = assertIs<RegistrationUiState.Data>(state)
        assertEquals(1, data.snapshot.events.size)
        assertEquals(1, data.snapshot.issues.size)
    }

    @Test
    fun distinguishesEmptyAndFullyBrokenLogs() {
        assertIs<RegistrationUiState.Empty>(parser.parse("\n"))
        assertIs<RegistrationUiState.ParseError>(parser.parse("{broken"))
    }

    @Test
    fun preservesUnknownStateAndTransport() {
        val state = parser.parse(
            """{"timestampMs":1,"subId":1,"state":"VENDOR_STATE","transportType":77,"featureTags":[]}""",
        )
        val event = assertIs<RegistrationUiState.Data>(state).snapshot.latestBySubId.single()
        assertEquals(RegistrationState.UNKNOWN, event.normalizedState)
        assertEquals(77, event.transportType)
        assertEquals(emptyList(), event.featureTags)
    }

    @Test
    fun decodesUnregisteredReasonFields() {
        val state = parser.parse(
            """{"timestampMs":1,"subId":2,"state":"UNREGISTERED","reasonCode":1000,"extraCode":999,"extraMessage":"General_Error17"}""",
        )
        val event = assertIs<RegistrationUiState.Data>(state).snapshot.latestBySubId.single()
        assertEquals(1000, event.reasonCode)
        assertEquals(999, event.extraCode)
        assertEquals("General_Error17", event.extraMessage)
    }
}
