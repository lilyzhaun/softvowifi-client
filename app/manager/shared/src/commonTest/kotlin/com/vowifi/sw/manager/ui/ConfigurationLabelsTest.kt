package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigurationLabelsTest {
    @Test
    fun separatorInsideCarrierNameIsKeptInThePrimaryTitle() {
        val header = configurationHeader("SIM 2 · 🇺🇸 Example · Mobile · 310 / 260 · •••• 1234")
        assertEquals("SIM 2 · 🇺🇸 Example · Mobile", header.title)
        assertEquals("310 / 260 · •••• 1234", header.details)
    }

    @Test
    fun canonicalSimLabelKeepsIdentityDetailsOutsideThePrimaryTitle() {
        val header = configurationHeader("SIM 2 · Test carrier · 001 / 01 · •••• 1234")
        assertEquals("SIM 2 · Test carrier", header.title)
        assertEquals("001 / 01 · •••• 1234", header.details)
    }

    @Test
    fun labelsWithoutCanonicalSimStructureArePreserved() {
        listOf("主卡", "测试 · 手动配置 · 已停止", "SIM 1 · Test carrier").forEach { label ->
            assertEquals(label, configurationHeader(label).title)
            assertNull(configurationHeader(label).details)
        }
    }
}
