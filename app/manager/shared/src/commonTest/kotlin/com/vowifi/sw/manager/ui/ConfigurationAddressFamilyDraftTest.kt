package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationAddressFamilyDraftTest {
    @Test fun editedFamilyRetainsItsRevisionAcrossRefreshAndUsesNextStart() {
        val row = instance("")
        val draft = ConfigurationEditorDraft(row)
        assertTrue(draft.replaceEpdgAddressFamily("ipv6-only"))
        assertEquals(1, draft.modifiedCount)
        assertFalse(draft.onlyNextCallChanges)
        val fresh = row.copy(epdgAddressFamily = "ipv4-only", configRevision = NEXT, epdgFqdn = "fresh.example")
        draft.syncFrom(fresh)
        val retained = ConfigurationEditorDraft(fresh, draft.retained())
        assertEquals("ipv6-only", retained.epdgAddressFamily)
        assertEquals(INITIAL, retained.overrides().expectedRevision)
        assertEquals("fresh.example", retained.epdgFqdn.text.toString())
        retained.discard()
        assertEquals("ipv4-only", retained.epdgAddressFamily)
        assertEquals(NEXT, retained.overrides().expectedRevision)
        assertFalse(retained.dirty)
        retained.replaceEpdgAddressFamily("invalid")
        assertFalse(retained.validate())
        assertNotNull(retained.addressFamilyError)
        assertTrue(retained.errors.isEmpty())
    }

    @Test fun unknownClearNeedsAuthoritativeConfirmationAndPendingOrUnconfirmedNeverReplays() {
        val row = instance(null)
        val draft = ConfigurationEditorDraft(row)
        assertFalse(draft.replaceEpdgAddressFamily("ipv4-only"))
        assertTrue(draft.replaceEpdgAddressFamily(""))
        assertFalse(draft.replaceEpdgAddressFamily("ipv4-only"))
        var writes = 0
        draft.submit { _, payload, complete ->
            writes++; assertEquals("", payload.epdgAddressFamily)
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(epdgAddressFamily = "", configRevision = NEXT)))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertTrue(draft.replaceEpdgAddressFamily("ipv4-only"))
        var completion: ((ConfigurationApplyOutcome) -> Unit)? = null
        draft.submit { _, _, complete -> writes++; completion = complete; ConfigurationApplyAcceptance.Accepted }
        assertFalse(draft.replaceEpdgAddressFamily(""))
        completion!!(ConfigurationApplyOutcome.Unconfirmed)
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals("ipv4-only", draft.epdgAddressFamily)
        val retained = ConfigurationEditorDraft(row.copy(epdgAddressFamily = "", configRevision = NEXT), draft.retained())
        assertEquals(ConfigurationEditStatus.Unconfirmed, retained.status)
        assertEquals(2, writes)
    }

    private fun instance(family: String?) = ConfigurationInstance(ConfigurationInstanceId("opaque"), "SIM 1", ConfigurationLifecycle.RUNNING,
        ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01",
        draftOwnerId = requireNotNull(SimOwnerId.fromHmacSha256(ByteArray(32) { 3 })), configVersion = 1,
        configRevision = INITIAL, epdgAddressFamily = family)
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
