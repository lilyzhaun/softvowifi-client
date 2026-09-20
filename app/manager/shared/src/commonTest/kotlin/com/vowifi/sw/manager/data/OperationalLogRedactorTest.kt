package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalLogRedactorTest {
    @Test
    fun redactsSensitiveValuesWithStableSafeRepresentations() {
        val cases = listOf(
            RedactionCase("iccid=TEST-99999999999991234", "[identity]"),
            RedactionCase("imsi=TEST-001010999999999", "[identity]"),
            RedactionCase("msisdn=TEST-+1-555-010-1234", "[identity]"),
            RedactionCase("phone=TEST-+1-555-010-5678", "[identity]"),
            RedactionCase("sip:TEST-001010999999999@example.invalid", "[identity]"),
            RedactionCase("tel:+1-555-010-1234", "[identity]"),
            RedactionCase("impi=TEST-001010999999999@example.invalid", "[identity]"),
            RedactionCase("impu=sip:TEST-001010999999999@example.invalid", "[identity]"),
            RedactionCase("CK=0123456789abcdef0123456789abcdef", "[secret]"),
            RedactionCase("IK=0123456789abcdef0123456789abcdef", "[secret]"),
            RedactionCase("RES=0123456789abcdef", "[secret]"),
            RedactionCase("AUTN=0123456789abcdef0123456789abcdef", "[secret]"),
            RedactionCase("RAND=0123456789abcdef0123456789abcdef", "[secret]"),
            RedactionCase("KASME=0123456789abcdef0123456789abcdef", "[secret]"),
            RedactionCase("PSK=0123456789abcdef0123456789abcdef", "[secret]"),
            RedactionCase("password = TEST-password-value", "[secret]"),
            RedactionCase("TOKEN: TEST-token-value", "[secret]"),
            RedactionCase("certificate=TEST-certificate-value", "[secret]"),
            RedactionCase("digest 0123456789abcdef0123456789abcdef", "digest [secret]"),
            RedactionCase("open /data/adb/test-root/engine.log", "open [path]"),
            RedactionCase("read /proc/9999/cmdline", "read [path]"),
            RedactionCase("apk /data/app/test.example/base.apk", "apk [path]"),
            RedactionCase("remote=2001:db8::1", "[identity]"),
            RedactionCase("remote=[2001:db8::1]:5060", "[identity]"),
            RedactionCase("2001:db8::1", "[identity]"),
            RedactionCase("address=10.0.0.2:5060", "[identity]"),
            RedactionCase("dial +1-555-010-1234", "dial [identity]"),
            RedactionCase("sms_body=hello from subscriber tonight", "[secret]"),
            RedactionCase("body=multi word private content", "[secret]"),
            RedactionCase("[2001:db8::1]", "[identity]"),
            RedactionCase("dial +855 (12) 345 678", "dial [identity]"),
        )

        cases.forEach { case ->
            val actual = OperationalLogRedactor.redact(case.input)

            assertEquals(case.expected, actual, case.input)
        }
    }

    @Test
    fun masksOnlyBracketedInstanceWithLastFourDigits() {
        val actual = OperationalLogRedactor.redact(
            "2026-08-17 12:34:56.789 [INFO] [engine] [TEST-99999999999991234] engine started",
        )

        assertEquals(
            "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] engine started",
            actual,
        )
    }

    @Test
    fun boundedSafeSummaryReplacesPlaceholdersWithNeutralMarkerAndBoundsLength() {
        val input = "registered uri=sip:user@192.168.1.1:5060 token=abc123456789012345678901234567890 path=/data/local/tmp"
        val summary = OperationalLogRedactor.boundedSafeSummary(input)

        assertTrue(summary.length <= OPERATIONAL_LOG_SAFE_SUMMARY_MAX_LENGTH)
        assertFalse(summary.contains("[identity]"))
        assertFalse(summary.contains("[secret]"))
        assertFalse(summary.contains("[path]"))
        assertTrue(summary.contains("敏感信息已隐藏"))
        assertFalse(OperationalLogRedactor.requiresRedaction(summary))
    }

    @Test
    fun isPrivateUiTextAllowsBoundedNetworkAddressesWhileHidingSensitiveData() {
        assertTrue(OperationalLogRedactor.isPrivateUiText("192.0.2.1"))
        assertTrue(OperationalLogRedactor.isPrivateUiText("2001:db8::1"))
        val summary = OperationalLogRedactor.boundedSafeSummary("pcscf=192.0.2.1")
        assertFalse(summary.contains("192.0.2.1"))
        assertFalse(OperationalLogRedactor.requiresRedaction(summary))
        assertFalse(OperationalLogRedactor.isPrivateUiText("sip:user@example.invalid"))
        assertFalse(OperationalLogRedactor.isPrivateUiText("dial +855 (12) 345 678"))
        assertFalse(OperationalLogRedactor.isPrivateUiText("sms_body=private words"))
    }

    @Test
    fun preservesNonSensitiveShortNumbersAndReasonCodes() {
        val actual = OperationalLogRedactor.redact("port=5060 subId=7 reason=403 retry=12")

        assertTrue(actual.contains("5060"))
        assertTrue(actual.contains("subId=7"))
        assertTrue(actual.contains("reason=403"))
        assertFalse(actual.contains("[identity]"))
        assertFalse(actual.contains("[secret]"))
    }
}

private data class RedactionCase(
    val input: String,
    val expected: String,
)
