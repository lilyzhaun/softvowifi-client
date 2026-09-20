package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DnsWorkPlannerTest {
    @Test
    fun requiresEndpointForEligibleActiveSimMissingConfig() {
        val planNeedsDns = DnsWorkPlanner.needsEndpoint(
            active = listOf(eligible(SIM_KEY_A, 0)),
            existingKeys = emptySet(),
            rows = emptyList(),
        )

        assertTrue(planNeedsDns)
        assertEquals(
            emptyList(),
            DnsWorkPlanner.repairs(
                active = listOf(eligible(SIM_KEY_A, 0)),
                rows = emptyList(),
                endpoint = DnsEndpoint.v4("192.0.2.53"),
            ),
        )
    }

    @Test
    fun requiresEndpointForOemBackedActiveSimMissingConfig() {
        val planNeedsDns = DnsWorkPlanner.needsEndpoint(
            active = listOf(
                simActive(SIM_KEY_A, 0, mcc = "505", mnc = "03"),
                simActive(SIM_KEY_B, 1, mcc = "454", mnc = "03"),
            ),
            existingKeys = emptySet(),
            rows = emptyList(),
        )

        assertTrue(planNeedsDns)
    }

    @Test
    fun requiresEndpointForUnknownValidPlmnMissingConfig() {
        val planNeedsDns = DnsWorkPlanner.needsEndpoint(
            active = listOf(simActive(SIM_KEY_A, 0, mcc = "999", mnc = "99")),
            existingKeys = emptySet(),
            rows = emptyList(),
        )

        assertTrue(planNeedsDns)
    }

    @Test
    fun skipsEndpointForMalformedPlmnMissingConfig() {
        val planNeedsDns = DnsWorkPlanner.needsEndpoint(
            active = listOf(simActive(SIM_KEY_A, 0, mcc = "99", mnc = "99")),
            existingKeys = emptySet(),
            rows = emptyList(),
        )

        assertFalse(planNeedsDns)
    }

    @Test
    fun selectsOnlyActiveBlankDnsRowsAndPreservesOverrides() {
        val endpoint = DnsEndpoint.v4("192.0.2.53")
        val activeBlank = row(
            key = SIM_KEY_A,
            dns = "",
            transport = ConfigurationTransport.SIM2,
            smsc = FAKE_SMSC,
            epdgFqdn = FAKE_EPDG,
            imsRealm = FAKE_REALM,
        )

        val needsDns = DnsWorkPlanner.needsEndpoint(
            active = listOf(eligible(SIM_KEY_A, 0)),
            existingKeys = setOf(SIM_KEY_A),
            rows = listOf(activeBlank),
        )
        val repairs = DnsWorkPlanner.repairs(
            active = listOf(eligible(SIM_KEY_A, 0)),
            rows = listOf(activeBlank),
            endpoint = endpoint,
        )

        assertTrue(needsDns)
        val repair = repairs.single()
        assertEquals(SIM_KEY_A, repair.key)
        assertEquals(activeBlank.instanceId, repair.instanceId)
        assertEquals(ConfigurationTransport.SIM2, repair.overrides.transport)
        assertEquals(FAKE_SMSC, repair.overrides.smsc)
        assertEquals(FAKE_EPDG, repair.overrides.epdgFqdn)
        assertEquals(FAKE_REALM, repair.overrides.imsRealm)
        assertEquals("192.0.2.53:53", repair.overrides.dns)
    }

    @Test
    fun skipsInactiveConfigOnlyAndNonblankDnsRows() {
        val inactiveBlank = row(key = SIM_KEY_B, dns = "")
        val activeFilled = row(key = SIM_KEY_A, dns = "192.0.2.1:53")
        val ineligibleMissing = simActive(SIM_KEY_C, 1, mcc = null, mnc = null)

        val needsDns = DnsWorkPlanner.needsEndpoint(
            active = listOf(eligible(SIM_KEY_A, 0), ineligibleMissing),
            existingKeys = setOf(SIM_KEY_A, SIM_KEY_B),
            rows = listOf(activeFilled, inactiveBlank),
        )
        val repairs = DnsWorkPlanner.repairs(
            active = listOf(eligible(SIM_KEY_A, 0), ineligibleMissing),
            rows = listOf(activeFilled, inactiveBlank),
            endpoint = DnsEndpoint.v6("2001:db8::53"),
        )

        assertFalse(needsDns)
        assertEquals(emptyList(), repairs)
    }

    @Test
    fun domainObjectsRedactKeysAndEndpoints() {
        val row = row(key = SIM_ICCID_19, dns = "")
        val repair = DnsWorkPlanner.repairs(
            active = listOf(eligible(SIM_ICCID_19, 0)),
            rows = listOf(row),
            endpoint = DnsEndpoint.v4("192.0.2.53"),
        ).single()

        assertFalse(row.toString().contains(SIM_ICCID_19))
        assertFalse(repair.toString().contains(SIM_ICCID_19))
        assertFalse(repair.toString().contains("192.0.2.53"))
        assertFalse(repair.toString().contains(repair.overrides.dns))
        assertPrivate(row.toString())
        assertPrivate(repair.toString())
        assertEquals("192.0.2.53:53", repair.overrides.dns)
    }

    private fun eligible(key: String, slotIndex: Int) =
        simActive(key, slotIndex, mcc = "310", mnc = "260")

    private fun row(
        key: String,
        dns: String,
        transport: ConfigurationTransport = ConfigurationTransport.WIFI,
        smsc: String = "",
        epdgFqdn: String = "",
        imsRealm: String = "",
    ) = DnsConfigRow(
        instanceId = ConfigurationInstanceId("cfg-$key"),
        key = key,
        dns = dns,
        transport = transport,
        smsc = smsc,
        epdgFqdn = epdgFqdn,
        imsRealm = imsRealm,
    )
}
