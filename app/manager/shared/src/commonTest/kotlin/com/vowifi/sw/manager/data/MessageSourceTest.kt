package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageSourceTest {
    @Test
    fun enumeratesEveryFixtureMode() {
        assertEquals(7, MessageFixtureMode.entries.size)

        MessageFixtureMode.entries.forEach { mode ->
            val state = FixtureMessageSource(mode).state.value
            when (mode) {
                MessageFixtureMode.LOADING -> assertIs<MessageUiState.Loading>(state)
                MessageFixtureMode.EMPTY -> assertIs<MessageUiState.Empty>(state)
                MessageFixtureMode.READY -> {
                    val ready = assertIs<MessageUiState.Ready>(state)
                    assertEquals(MessageOrigin.DESKTOP_FIXTURE, ready.origin)
                    assertFalse(ready.sendBusy)
                    assertTrue(ready.records.any { it is MessageRecord.Incoming })
                    val outgoing = ready.records.filterIsInstance<MessageRecord.Outgoing>()
                    assertEquals(OutgoingMessageStatus.entries.toSet(), outgoing.map { it.status }.toSet())
                }
                MessageFixtureMode.READ_ONLY -> {
                    val ready = assertIs<MessageUiState.Ready>(state)
                    assertEquals(MessageOrigin.DESKTOP_FIXTURE, ready.origin)
                    assertFalse(ready.composerEnabled)
                    assertTrue(ready.records.isNotEmpty())
                    assertEquals(ActiveEngineSelector.NONE_MESSAGE, ready.attachmentNotice)
                }
                MessageFixtureMode.ERROR -> assertIs<MessageUiState.Failure>(state)
                MessageFixtureMode.PERMISSION -> assertIs<MessageUiState.PermissionDenied>(state)
                MessageFixtureMode.UNAVAILABLE -> assertIs<MessageUiState.Unavailable>(state)
            }
        }
    }

    @Test
    fun refreshRecoversToReadyExceptUnavailable() {
        val recoverable = listOf(
            MessageFixtureMode.LOADING,
            MessageFixtureMode.EMPTY,
            MessageFixtureMode.READY,
            MessageFixtureMode.READ_ONLY,
            MessageFixtureMode.ERROR,
            MessageFixtureMode.PERMISSION,
        )

        recoverable.forEach { mode ->
            val source = FixtureMessageSource(mode)
            source.refresh()
            val ready = assertIs<MessageUiState.Ready>(source.state.value)
            assertEquals(MessageOrigin.DESKTOP_FIXTURE, ready.origin)
        }

        val unavailable = FixtureMessageSource(MessageFixtureMode.UNAVAILABLE)
        unavailable.refresh()
        assertIs<MessageUiState.Unavailable>(unavailable.state.value)
    }

    @Test
    fun productionUnavailableSourceStaysUnavailableAfterRefresh() {
        val source = UnavailableMessageSource()

        assertIs<MessageUiState.Unavailable>(source.state.value)
        source.refresh()
        assertIs<MessageUiState.Unavailable>(source.state.value)
    }

    @Test
    fun fixtureVisibleStringsStayPrivacySafe() {
        MessageFixtureMode.entries.forEach { mode ->
            MessageFixtures.visibleStrings(FixtureMessageSource(mode).state.value).forEach(::assertMessagePrivacy)
        }
        MessageFixtures.visibleStrings(UnavailableMessageSource().state.value).forEach(::assertMessagePrivacy)
        val noticed = MessageUiState.Ready(
            records = emptyList(),
            origin = MessageOrigin.ENGINE,
            attachmentNotice = STORAGE_NOTICE,
        )
        val synced = noticed.copy(attachmentNotice = STORAGE_SYNC_NOTICE)
        assertTrue(STORAGE_NOTICE in MessageFixtures.visibleStrings(noticed))
        assertTrue(STORAGE_SYNC_NOTICE in MessageFixtures.visibleStrings(synced))
        MessageFixtures.visibleStrings(noticed).forEach(::assertMessagePrivacy)
        MessageFixtures.visibleStrings(synced).forEach(::assertMessagePrivacy)
        listOf(
            STORAGE_NOTICE,
            STORAGE_SYNC_NOTICE,
            STORAGE_OPEN_NOTICE,
            MESSAGE_IDENTITY_NOTICE,
        ).forEach(::assertMessagePrivacy)
    }

    @Test
    fun unknownFixtureUsesDistinctBodyAndHonestStatus() {
        val unknown = MessageFixtures.ready().records
            .filterIsInstance<MessageRecord.Outgoing>()
            .single { it.status == OutgoingMessageStatus.UNKNOWN }

        assertEquals("这是一条示例短信", unknown.preview)
        assertEquals(OutgoingMessageStatus.UNKNOWN, unknown.status)
        assertFalse(unknown.preview.contains("结果未知"))
    }

    @Test
    fun fromWireMapsEveryKnownValue() {
        assertEquals(7, WIRE_MODES.size)
        assertEquals(MessageFixtureMode.entries.toSet(), WIRE_MODES.values.toSet())
        WIRE_MODES.forEach { (wire, mode) ->
            assertEquals(mode, MessageFixtureMode.fromWire(wire))
            assertEquals(
                FixtureMessageSource(mode).state.value,
                FixtureMessageSource(wire).state.value,
            )
        }
    }

    @Test
    fun fromWireRejectsUnknownValues() {
        listOf("data", "failure", "warning", "READY", "").forEach { wire ->
            assertFailsWith<IllegalArgumentException> { MessageFixtureMode.fromWire(wire) }
            assertFailsWith<IllegalArgumentException> { FixtureMessageSource(wire) }
        }
    }

    @Test
    fun fixtureAndUnavailableSendSmsStayHonest() {
        MessageFixtureMode.entries.forEach { mode ->
            val source = FixtureMessageSource(mode)
            val before = source.state.value
            source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
            assertEquals(before, source.state.value)
        }

        val unavailable = UnavailableMessageSource()
        val before = unavailable.state.value
        unavailable.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        assertEquals(before, unavailable.state.value)
        assertIs<MessageUiState.Unavailable>(unavailable.state.value)
    }

    @Test
    fun deliveryReportedIsNotIncomingMtReceipt() {
        val ready = assertIs<MessageUiState.Ready>(
            FixtureMessageSource(MessageFixtureMode.READY).state.value,
        )
        val reported = ready.records.filterIsInstance<MessageRecord.Outgoing>()
            .filter { it.status == OutgoingMessageStatus.DELIVERY_REPORTED }

        assertTrue(reported.isNotEmpty())
        ready.records.forEach { record ->
            when (record) {
                is MessageRecord.Incoming -> assertTrue(record.isLocalMtReceipt)
                is MessageRecord.Outgoing -> {
                    assertFalse(record.isLocalMtReceipt)
                    when (record.status) {
                        OutgoingMessageStatus.QUEUED,
                        OutgoingMessageStatus.ACCEPTED,
                        OutgoingMessageStatus.DELIVERY_REPORTED,
                        OutgoingMessageStatus.FAILED,
                        OutgoingMessageStatus.UNKNOWN -> Unit
                    }
                }
            }
        }
    }
}

private const val SYNTH_NUMBER = "+155501"
private const val SYNTH_TEXT = "synth-one"

private val WIRE_MODES = mapOf(
    "loading" to MessageFixtureMode.LOADING,
    "empty" to MessageFixtureMode.EMPTY,
    "ready" to MessageFixtureMode.READY,
    "read-only" to MessageFixtureMode.READ_ONLY,
    "error" to MessageFixtureMode.ERROR,
    "permission" to MessageFixtureMode.PERMISSION,
    "unavailable" to MessageFixtureMode.UNAVAILABLE,
)

private val phoneLike = Regex("""\+?\d{8,}""")
private val identityUri = Regex("""(?i)\b(?:sip|tel):""")
private val credentialWord = Regex("""(?i)\b(?:password|token|certificate|credential|iccid|imsi|msisdn|impi|impu)\b""")
private val enginePayload = Regex("""(?i)\b(?:smsReceived|sendSms|smsSent)\b""")

private fun assertMessagePrivacy(text: String) {
    assertPrivate(text)
    assertFalse(phoneLike.containsMatchIn(text), "leaked phone-like digits in $text")
    assertFalse(identityUri.containsMatchIn(text), "leaked SIP/TEL URI in $text")
    assertFalse(credentialWord.containsMatchIn(text), "leaked subscriber or credential word in $text")
    assertFalse(enginePayload.containsMatchIn(text), "leaked raw engine message in $text")
    assertFalse(text.contains("/data/") || text.contains("/proc/"), "leaked path in $text")
}
