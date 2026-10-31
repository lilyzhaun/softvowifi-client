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
        val current = baseline.copy(epdgFqdn = "user.epdg.invalid", dns = "1.1.1.1", dnsOverrides = mapOf("auto" to "1.1.1.1"))
        val refreshed = baseline.copy(
            transport = ConfigurationTransport.WIFI,
            smsc = "+123",
            epdgFqdn = "server.epdg.invalid",
            imsRealm = "server.realm.invalid",
            dns = "8.8.8.8:53",
            dnsOverrides = mapOf("wifi" to "8.8.8.8:53"),
        )

        assertEquals(
            refreshed.copy(epdgFqdn = current.epdgFqdn, dnsOverrides = mapOf("wifi" to "8.8.8.8:53", "auto" to "1.1.1.1")),
            synchronizedConfigurationDraft(current, baseline, refreshed),
        )
    }

    @Test
    fun dnsFieldFollowsSelectedTransportAndKeepsEachInterfaceOverride() {
        val draft = ConfigurationEditorDraft(instance("sim-1").copy(
            transport = ConfigurationTransport.WIFI,
            dns = "192.0.2.53:53",
            dnsOverrides = mapOf("wifi" to "192.0.2.53:53"),
        ))
        draft.transport = ConfigurationTransport.Interface("tun0")
        assertEquals("", draft.dns.text.toString())
        assertEquals(emptySet(), draft.modifiedFields(), "switching transport alone must not mark DNS edited")
        draft.dns.edit { replace(0, length, "198.51.100.53") }
        draft.transport = ConfigurationTransport.WIFI
        assertEquals("192.0.2.53:53", draft.dns.text.toString())
        draft.transport = ConfigurationTransport.Interface("tun0")
        assertEquals("198.51.100.53", draft.dns.text.toString())
        val overrides = draft.overrides()
        assertEquals("198.51.100.53", overrides.dns)
        assertEquals(mapOf("wifi" to "192.0.2.53:53", "iface:tun0" to "198.51.100.53"), overrides.dnsOverrides)
        assertEquals(setOf(ConfigurationFieldKey.Dns), draft.modifiedFields())
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

    @Test
    fun dirtyDraftKeepsOriginalRevisionAfterRefreshAndRetention() {
        val firstRevision = "r1_abcdefghijklmnopabcdefghijklmnop"
        val nextRevision = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val initial = instance("sim-1").copy(configVersion = 1, configRevision = firstRevision)
        val draft = ConfigurationEditorDraft(initial)
        draft.epdgFqdn.edit { replace(0, length, "user.example.test") }
        draft.syncFrom(initial.copy(configRevision = nextRevision))
        assertEquals(firstRevision, draft.overrides().expectedRevision,
            "refresh must not authorize an old edit against a newly rebuilt config")
        val retained = ConfigurationEditorDraft(initial.copy(configRevision = nextRevision), draft.retained())
        assertEquals(firstRevision, retained.overrides().expectedRevision)
        assertEquals("user.example.test", retained.epdgFqdn.text.toString())
        retained.discard()
        assertEquals(nextRevision, retained.overrides().expectedRevision)
    }

    @Test
    fun cleanDraftFollowsCurrentRevisionWithoutCreatingAnEdit() {
        val initial = instance("sim-1").copy(configVersion = 1, configRevision = "r1_abcdefghijklmnopabcdefghijklmnop")
        val draft = ConfigurationEditorDraft(initial)
        val incoming = initial.copy(configRevision = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        draft.syncFrom(incoming)
        assertEquals(incoming.configRevision, draft.overrides().expectedRevision)
        assertEquals(false, draft.dirty)
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
