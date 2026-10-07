package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MessageSenderDisplayTest {
    @Test fun originatorNamesAndNumericAddressesAreDisplayable() {
        listOf("ExampleVerify", "Example Bank", "Example123", "测试服务", "+12025550123", "456").forEach {
            assertEquals(it, messageSenderDisplay(it))
        }
    }

    @Test fun internalKeysUrisAndAnonymousValuesUseFallback() {
        listOf(null, "", "anonymous", "raw:Example", "sip:peer@example.invalid", "tel:123", "peer@example.invalid", "https://example.invalid").forEach {
            assertEquals("未知号码", messageSenderDisplay(it))
            assertEquals("短信", messageSenderDisplay(it, "短信"))
        }
        assertEquals("Example", messageSenderDisplay("\u202EExample\u2069\u0000"))
    }

    @Test fun truncationPreservesUnicodeCodePoints() {
        val result = messageSenderDisplay("服务".repeat(31) + "\uD83D\uDE80\uD83D\uDE80suffix")
        assertEquals(66, result.length)
        assertFalse(result.last().isHighSurrogate())
    }
}
