package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationControlCommandTest {
    @Test
    fun argvContainsOnlyScriptConfigActionAndDigitsNeverJson() {
        val iccid = "8986000000000000001"
        val payload = ConfigurationCodec.encode(
            ConfigurationOverrides(
                transport = ConfigurationTransport.WIFI,
                smsc = FAKE_SMSC,
                epdgFqdn = FAKE_EPDG,
                imsRealm = FAKE_REALM,
                dns = FAKE_DNS,
            ),
        )

        val showTokens = ConfigurationControlCommand.showLine(iccid).split(' ')
        val applyTokens = ConfigurationControlCommand.applyLine(iccid).split(' ')
        val listTokens = ConfigurationControlCommand.listLine().split(' ')

        assertEquals(
            listOf(ConfigurationControlCommand.SCRIPT, "config", "show", iccid),
            showTokens,
        )
        assertEquals(
            listOf(ConfigurationControlCommand.SCRIPT, "config", "apply", iccid),
            applyTokens,
        )
        assertEquals(listOf(ConfigurationControlCommand.SCRIPT, "list"), listTokens)
        assertTrue(showTokens.last().all(Char::isDigit))
        assertTrue(applyTokens.last().all(Char::isDigit))
        assertEquals(60L, ConfigurationControlCommand.APPLY_TIMEOUT_SECONDS)
        assertEquals("config-0", ConfigurationControlCommand.handle(0))
        assertEquals("config-1", ConfigurationControlCommand.handle(1))
        assertFalse(applyTokens.any { token -> token.contains('{') || token.contains(payload) })
        assertFalse(showTokens.joinToString(" ").contains(payload))
        assertFalse(payload in ConfigurationControlCommand.applyLine(iccid))
        assertFailsWith<IllegalArgumentException> {
            ConfigurationControlCommand.showLine("8986abc")
        }
        assertFailsWith<IllegalArgumentException> {
            ConfigurationControlCommand.applyLine("")
        }
    }

    @Test
    fun createArgvContainsOnlyScriptConfigCreateAndDigitsNeverJson() {
        val iccid = "8986000000000000001"
        val subscriptionId = 2
        val slot = 1
        val payload = """{"iccid":"$iccid","subscriptionId":$subscriptionId,"imsi":"","imei":"","slot":$slot}"""

        val createTokens = ConfigurationControlCommand.createLine(subscriptionId, slot).split(' ')
        val createLine = createTokens.joinToString(" ")

        assertEquals(
            listOf(
                ConfigurationControlCommand.SCRIPT,
                "config",
                "create",
                subscriptionId.toString(),
                slot.toString(),
            ),
            createTokens,
        )
        assertEquals(60L, ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS)
        assertTrue(createTokens.takeLast(2).all { token -> token.isNotEmpty() && token.all(Char::isDigit) })
        assertFalse(createTokens.contains(iccid))
        assertFalse(createLine.contains(iccid))
        assertFalse(createTokens.any { token -> token.contains('{') || token.contains(payload) })
        assertFalse(payload in createLine)
        assertFailsWith<IllegalArgumentException> {
            ConfigurationControlCommand.createLine(-1, slot)
        }
        assertFailsWith<IllegalArgumentException> {
            ConfigurationControlCommand.createLine(subscriptionId, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            ConfigurationControlCommand.createLine(subscriptionId, -1)
        }
    }
}
