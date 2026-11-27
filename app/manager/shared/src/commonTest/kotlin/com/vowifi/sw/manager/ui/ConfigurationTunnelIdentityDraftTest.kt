package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationTunnelIdentityDraftTest {
    @Test fun catalogTypesSummaryDoesNotClaimAManualOrAuthenticatedIdentity() {
        val types = ConfigurationTunnelIdentityTypes(ConfigurationIkeIdType.KEY_ID, ConfigurationIkeIdType.FQDN, ConfigurationIkeIdType.FQDN)
        val facts = ConfigurationTunnelIdentityFacts(true, "catalog", types)
        val summary = identityFactsSummary(facts)
        assertTrue(summary.startsWith("运营商目录"))
        assertFalse("手动" in summary)
        assertFalse("已认证" in summary)
        assertTrue(identityFactsSummary(facts.copy(source = "user")).startsWith("手动"))
        assertEquals("未知；请刷新核对", identityFactsSummary(facts.copy(known = false)))
    }

    @Test fun groupMergeRetainsOldRevisionAndUsesRestartRatherThanCallOnly() {
        val initial = instance(ConfigurationTunnelIdentity())
        val draft = ConfigurationEditorDraft(initial)
        assertTrue(draft.replaceTunnelIdentity(selected))
        assertEquals(1, draft.modifiedCount)
        assertFalse(draft.onlyNextCallChanges)
        val fresh = initial.copy(configRevision = NEXT, epdgFqdn = "fresh.example.invalid")
        draft.syncFrom(fresh)
        assertEquals(selected, draft.overrides().tunnelIdentity)
        assertEquals(INITIAL, draft.overrides().expectedRevision)
        assertEquals("fresh.example.invalid", draft.epdgFqdn.text.toString())
        draft.discard()
        assertEquals(ConfigurationTunnelIdentity(), draft.tunnelIdentity)
        assertEquals(NEXT, draft.overrides().expectedRevision)
    }

    @Test fun unknownMustBeClearedAndIncompletePeerCannotSubmit() {
        val unknown = ConfigurationEditorDraft(instance(null))
        assertFalse(unknown.replaceTunnelIdentity(selected))
        assertTrue(unknown.replaceTunnelIdentity(ConfigurationTunnelIdentity()))
        assertFalse(unknown.replaceTunnelIdentity(selected))
        val draft = ConfigurationEditorDraft(instance(ConfigurationTunnelIdentity()))
        draft.replaceTunnelIdentity(ConfigurationTunnelIdentity(ConfigurationTunnelIdentityPolicy(peer = ConfigurationPeerIdentity(ConfigurationIkeIdType.FQDN, ""))))
        var calls = 0
        draft.submit { _, _, _ -> calls++; ConfigurationApplyAcceptance.Accepted }
        assertEquals(0, calls)
        assertNotNull(draft.identityError)
        draft.replaceTunnelIdentity(selected)
        assertNull(draft.identityError)
    }

    private fun instance(identity: ConfigurationTunnelIdentity?) = ConfigurationInstance(ConfigurationInstanceId("opaque"), "SIM 1", ConfigurationLifecycle.RUNNING,
        ConfigurationTransport.WIFI, "", "", "", "", "", "", "001", "01", configVersion = 1, configRevision = INITIAL, tunnelIdentity = identity)
    private val selected = ConfigurationTunnelIdentity(ConfigurationTunnelIdentityPolicy(ConfigurationIkeIdType.KEY_ID, ConfigurationIkeIdType.FQDN))
    companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
