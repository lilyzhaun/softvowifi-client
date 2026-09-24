package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.RegistrationState
import com.vowifi.sw.manager.model.RegistrationUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegistrationLogParserTest {
    private val parser = RegistrationLogParser()

    @Test
    fun recoversAdjacentCompleteRecordsWithoutFinalNewline() {
        val data = assertIs<RegistrationUiState.Data>(parser.parse(event(1) + event(2)))
        assertEquals(listOf(1L, 2L), data.snapshot.events.map { it.timestampMs })
        assertEquals(2L, data.snapshot.latestBySubId.single().timestampMs)
        assertEquals(emptyList(), data.snapshot.issues)
    }

    @Test
    fun framingDoesNotSplitBracesOrEscapedQuotesInsideStrings() {
        val first = """{"timestampMs":1,"subId":555,"state":"UNREGISTERED","extraMessage":"quoted \" }{ \\ end","unknown":{"nested":[{}]}}"""
        val data = assertIs<RegistrationUiState.Data>(parser.parse(first + event(2)))
        assertEquals(2, data.snapshot.events.size)
        assertEquals(emptyList(), data.snapshot.issues)
    }

    @Test
    fun corruptionRemainsVisibleWithoutLeakingInput() {
        for (broken in listOf("synthetic-secret" + event(1), event(1) + "synthetic-secret",
            """{"timestampMs":"synthetic-secret","subId":555,"state":"UNREGISTERED"}""",
            """{"timestampMs":1,"subId":555,"state":"synthetic-secret""")) {
            val state = parser.parse(broken)
            val issues = when (state) {
                is RegistrationUiState.Data -> state.snapshot.issues
                is RegistrationUiState.ParseError -> state.issues
                else -> error("corruption was hidden")
            }
            assertEquals(1, issues.size)
            assertEquals("IMS 观测记录格式无效", issues.single().message)
        }
    }

    @Test
    fun limitsLatestLogicalRecordsEvenWhenRowsAreJoined() {
        val content = (1..100).joinToString("") { event(it) }
        val data = assertIs<RegistrationUiState.Data>(parser.parse(content))
        assertEquals((51L..100L).toList(), data.snapshot.events.map { it.timestampMs })
        assertEquals(emptyList(), data.snapshot.issues)
    }

    private fun event(timestamp: Int) =
        """{"timestampMs":$timestamp,"subId":555,"state":"UNREGISTERED"}"""

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
