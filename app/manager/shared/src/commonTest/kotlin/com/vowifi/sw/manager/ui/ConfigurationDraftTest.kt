package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ConfigurationDraftTest {
    @Test
    fun draftValuesNeverExposeEditableFieldsInDiagnostics() {
        val values = ConfigurationDraftValues(ConfigurationTransport.AUTO, "", "private.invalid", "", "")
        assertEquals("ConfigurationDraftValues", values.toString())
    }

    @Test
    fun refreshedInstanceKeepsOnlyDirtyDraftFields() {
        val baseline = ConfigurationDraftValues(
            transport = ConfigurationTransport.AUTO,
            smsc = "",
            epdgFqdn = "old.epdg.invalid",
            imsRealm = "old.realm.invalid",
            dns = "",
        )
        val current = baseline.copy(epdgFqdn = "user.epdg.invalid", dns = "1.1.1.1")
        val refreshed = baseline.copy(
            transport = ConfigurationTransport.WIFI,
            smsc = "+123",
            epdgFqdn = "server.epdg.invalid",
            imsRealm = "server.realm.invalid",
            dns = "8.8.8.8",
        )

        assertEquals(
            refreshed.copy(epdgFqdn = current.epdgFqdn, dns = current.dns),
            synchronizedConfigurationDraft(current, baseline, refreshed),
        )
    }

    @Test
    fun independentDraftsDoNotShareOverrides() {
        val sim1 = ConfigurationDraftValues(ConfigurationTransport.WIFI, "", "sim1.epdg.invalid", "", "")
        val sim2 = ConfigurationDraftValues(ConfigurationTransport.SIM2, "", "sim2.epdg.invalid", "", "")

        assertEquals("sim1.epdg.invalid", sim1.epdgFqdn)
        assertEquals("sim2.epdg.invalid", sim2.epdgFqdn)
        assertEquals(ConfigurationTransport.WIFI, sim1.transport)
        assertEquals(ConfigurationTransport.SIM2, sim2.transport)
    }

    @Test
    fun pageOwnedDraftStoreRetainsEachInstanceDraftAcrossRecomposition() {
        val store = ConfigurationDraftStore()
        val sim1 = instance("sim-1")
        val sim2 = instance("sim-2")

        val firstSim1Draft = store.draftFor(sim1)
        val sim2Draft = store.draftFor(sim2)

        assertSame(firstSim1Draft, store.draftFor(sim1.copy(smsc = "refreshed")))
        assertNotSame(firstSim1Draft, sim2Draft)
    }

    private fun instance(id: String) = ConfigurationInstance(
        instanceId = ConfigurationInstanceId(id),
        displayLabel = "SIM ••••",
        lifecycle = ConfigurationLifecycle.STOPPED,
        transport = ConfigurationTransport.AUTO,
        smsc = "",
        epdgFqdn = "",
        imsRealm = "",
        dns = "",
        resolvedEpdgFqdn = "epdg.example.invalid",
        resolvedImsRealm = "ims.example.invalid",
        mcc = "001",
        mnc = "01",
    )
}
