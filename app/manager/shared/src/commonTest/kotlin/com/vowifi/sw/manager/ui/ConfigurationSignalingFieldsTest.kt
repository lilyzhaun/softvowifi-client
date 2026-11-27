package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConfigurationCallSignalingCodec
import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationSignalingFieldsTest {
    @Test fun everyEditorFieldMapsToItsOwnedLeafAndKeepsAllSiblings() {
        val values = listOf("true", "1200", "120", "uas", "prefer-update", "false",
            "true", "false", "all-18x", "true", "true", "false", "false", "sip", "context.example", "selected", "false")
        assertEquals(17, CallSignalingField.entries.size)
        assertEquals(17, CallSignalingField.entries.map { it.wire }.distinct().size)
        var group = ConfigurationCallSignaling()
        CallSignalingField.entries.zip(values).forEach { (field, value) ->
            val before = group
            group = field.withValue(group, value)
            assertEquals(value, field.value(group))
            CallSignalingField.entries.filter { it != field }.forEach { sibling ->
                assertEquals(sibling.value(before), sibling.value(group), sibling.wire)
            }
        }
        assertNotNull(ConfigurationCallSignalingCodec.encode(group))
        CallSignalingField.entries.forEach { field ->
            val cleared = field.withValue(group, null)
            assertNull(field.value(cleared))
            CallSignalingField.entries.filter { it != field }.forEach { sibling ->
                assertEquals(sibling.value(group), sibling.value(cleared), sibling.wire)
            }
        }
    }

    @Test fun textEditorsRejectInvalidNumbersAndDoNotExposeLegacyUpdateAsAChoice() {
        for (field in listOf(CallSignalingField.Expires, CallSignalingField.Minimum)) {
            for (text in listOf("89", "-1", "2147483648", "90junk")) {
                assertFailsWith<ConfigurationFormatException> { field.withValue(ConfigurationCallSignaling(), text) }
            }
            assertNull(field.value(field.withValue(ConfigurationCallSignaling(), "")))
        }
        val choices = CallSignalingField.RefreshMethod.choices()!!.map { it.value }
        assertEquals(listOf("", "invite", "prefer-update"), choices)
        assertFalse("update" in choices)
    }
}
