package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConfigurationTunnelTimingCodec
import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationTunnelTimingFieldsTest {
    @Test fun individualEditsCanCompleteBothRequiredHardLifetimesWithoutInventingOne() {
        var policy = ConfigurationTunnelTimingPolicy()
        assertNull(policy.ike.hardSeconds)
        assertNull(policy.child.hardSeconds)
        policy = TunnelTimingField.IkeHard.withText(policy, "2100")
        assertNull(policy.child.hardSeconds)
        policy = TunnelTimingField.ChildHard.withText(policy, "2400")
        ConfigurationTunnelTimingCodec.validate(policy)
        policy = TunnelTimingField.Dpd.withText(policy, "0")
        assertEquals(0L, policy.dpdSeconds)
        assertEquals("0", TunnelTimingField.Dpd.value(policy))
        assertEquals(14, TunnelTimingField.entries.size)
    }

    @Test fun numericErrorsAreBoundedAndCompletePolicyValidationStillRejectsConflicts() {
        val initial = ConfigurationTunnelTimingPolicy()
        listOf("", "-1", "1.5", "9223372036854775807", "text").forEach { text ->
            assertFailsWith<ConfigurationFormatException.InvalidTunnelTiming> { TunnelTimingField.IkeHard.withText(initial, text) }
        }
        listOf("NaN", "Infinity", "0.5", "").forEach { text ->
            assertFailsWith<ConfigurationFormatException.InvalidTunnelTiming> { TunnelTimingField.Backoff.withText(initial, text) }
        }
        assertFailsWith<ConfigurationFormatException.InvalidTunnelTiming> { TunnelTimingField.Retries.withText(initial, "33") }
        val incompatible = TunnelTimingField.ChildHard.withText(TunnelTimingField.IkeHard.withText(initial, "1"), "2400")
        assertFailsWith<ConfigurationFormatException.InvalidTunnelTiming> { ConfigurationTunnelTimingCodec.validate(incompatible) }
    }
}
