package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.StandardProvision
import com.vowifi.sw.manager.model.resolvedLine1
import com.vowifi.sw.manager.model.resolvedSmsc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicStandardProvisionTest {
    @Test
    fun hongKong45403UsesStandardThreeGppFormulaAndTwoDigitMnc() {
        val profile = StandardProvision.profileFor("454", "03")
        assertEquals("454", profile?.mcc)
        assertEquals("03", profile?.mnc)
        assertEquals(2, profile?.mncLen)
        assertEquals("epdg.epc.mnc003.mcc454.pub.3gppnetwork.org", profile?.epdgFqdn)
        assertEquals("ims.mnc003.mcc454.3gppnetwork.org", profile?.imsRealm)
        assertEquals("", profile?.smsc)
        assertEquals("", profile?.dns)
        assertEquals(ConfigurationTransport.WIFI, profile?.transport)
        assertEquals(true, profile?.enabled)
    }

    @Test
    fun threeDigitMncKeepsUnpaddedMncAndPaddedStandardFqdn() {
        val profile = StandardProvision.profileFor("310", "260")
        assertEquals("310", profile?.mcc)
        assertEquals("260", profile?.mnc)
        assertEquals(3, profile?.mncLen)
        assertEquals("epdg.epc.mnc260.mcc310.pub.3gppnetwork.org", profile?.epdgFqdn)
        assertEquals("ims.mnc260.mcc310.3gppnetwork.org", profile?.imsRealm)
    }

    @Test
    fun malformedNonAsciiAndNullPlmnYieldNoProfile() {
        val rejected = listOf(
            null to "260", "310" to null, "31" to "260", "3100" to "260", "31a" to "260",
            "310" to "2", "310" to "0260", "310" to "26a", " 310" to "260", "310" to " 09",
            "310" to "09 ", "３１０" to "260", "" to "260", "310" to "",
        )
        for ((mcc, mnc) in rejected) {
            assertNull(StandardProvision.profileFor(mcc, mnc), "MCC=$mcc MNC=$mnc")
        }
    }

    @Test
    fun automaticProfileClearsEpdgAndKeepsBlankPrivacyDefaults() {
        val automatic = StandardProvision.automaticProfileFor("454", "03")
        assertEquals("", automatic?.epdgFqdn)
        assertEquals("", automatic?.smsc)
        assertEquals("", automatic?.dns)
        assertEquals(ConfigurationTransport.WIFI, automatic?.transport)
        assertEquals("ims.mnc003.mcc454.3gppnetwork.org", automatic?.imsRealm)
    }

    @Test
    fun smscAndLine1HelpersKeepPrivacyDefaults() {
        assertEquals("+19995550123", resolvedSmsc("+19995550123", ""))
        assertEquals("+19995550123", resolvedSmsc(" +19995550123 ", ""))
        assertEquals("", resolvedSmsc(null, ""))
        assertEquals("", resolvedSmsc("+", ""))
        assertEquals("", resolvedSmsc("+1999abc", ""))
        assertEquals("+15550001", resolvedSmsc(null, "+15550001"))
        assertNull(resolvedLine1(null))
        assertNull(resolvedLine1("   "))
        assertEquals("+1555", resolvedLine1("  +1555  "))
    }
}
