package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationImsProtectionDraftTest {
    @Test fun refreshRetainsTheWholeEditedGroupAndDiscardFollowsFreshBaseline() {
        val row = instance().copy(imsProtection = selected)
        val draft = ConfigurationEditorDraft(row)
        val edited = ConfigurationImsProtection(listOf(ConfigurationImsSuite.NULL_SHA1, ConfigurationImsSuite.AES128_SHA1))
        assertTrue(draft.replaceImsProtection(edited))
        val fresh = row.copy(configRevision = NEXT, imsProtection = ConfigurationImsProtection(), epdgFqdn = "fresh.example")
        draft.syncFrom(fresh)
        val retained = ConfigurationEditorDraft(fresh, draft.retained())
        assertEquals(edited, retained.imsProtection)
        assertEquals(INITIAL, retained.overrides().expectedRevision)
        assertEquals("fresh.example", retained.epdgFqdn.text.toString())
        assertFalse(retained.onlyNextCallChanges)
        assertEquals(1, retained.modifiedCount)
        retained.discard()
        assertEquals(ConfigurationImsProtection(), retained.imsProtection)
        assertEquals(NEXT, retained.overrides().expectedRevision)
        assertFalse(retained.dirty)
    }

    @Test fun incompleteOrUDPConflictingGroupBlocksSaveWithoutContaminatingScalarErrors() {
        val draft = ConfigurationEditorDraft(instance())
        assertTrue(draft.replaceImsProtection(ConfigurationImsProtection(emptyList())))
        assertFalse(draft.validate())
        assertNotNull(draft.protectionError)
        assertTrue(draft.errors.isEmpty())
        assertTrue(draft.replaceImsProtection(selected))
        assertTrue(draft.validate())
        draft.imsSipSecurity = "swu_udp_aka"
        assertFalse(draft.validate())
        assertNotNull(draft.protectionError)
        assertTrue(draft.errors.isEmpty())
        draft.replaceImsProtection(ConfigurationImsProtection())
        assertTrue(draft.validate())
        val inherited = ConfigurationEditorDraft(instance().copy(imsPolicy = ConfigurationImsPolicy(security = "swu_udp_aka", securitySource = "catalog")))
        inherited.replaceImsProtection(selected)
        assertFalse(inherited.validate())
        inherited.imsSipSecurity = "tcp_ipsec_3gpp"
        assertTrue(inherited.validate())
    }

    @Test fun unknownClearRequiresConfirmationBeforeEditingAndUnconfirmedNeverReplays() {
        val row = instance().copy(imsProtection = null)
        val draft = ConfigurationEditorDraft(row)
        assertFalse(draft.replaceImsProtection(selected))
        assertTrue(draft.replaceImsProtection(ConfigurationImsProtection()))
        assertFalse(draft.replaceImsProtection(selected))
        var writes = 0
        draft.submit { _, payload, complete ->
            writes++
            assertEquals(ConfigurationImsProtection(), payload.imsProtection)
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(imsProtection = ConfigurationImsProtection(), configRevision = NEXT)))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertTrue(draft.replaceImsProtection(selected))
        var completion: ((ConfigurationApplyOutcome) -> Unit)? = null
        draft.submit { _, _, complete -> writes++; completion = complete; ConfigurationApplyAcceptance.Accepted }
        assertFalse(draft.replaceImsProtection(ConfigurationImsProtection()))
        completion!!(ConfigurationApplyOutcome.Unconfirmed)
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals(selected, draft.imsProtection)
        val restored = ConfigurationEditorDraft(row.copy(imsProtection = ConfigurationImsProtection(), configRevision = NEXT), draft.retained())
        assertEquals(ConfigurationEditStatus.Unconfirmed, restored.status)
        assertEquals(2, writes)
    }

    private fun instance() = ConfigurationInstance(ConfigurationInstanceId("opaque"), "SIM 1", ConfigurationLifecycle.RUNNING,
        ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01",
        draftOwnerId = requireNotNull(SimOwnerId.fromHmacSha256(ByteArray(32) { 3 })), configVersion = 1, configRevision = INITIAL, imsProtection = ConfigurationImsProtection())
    private val selected = ConfigurationImsProtection(listOf(ConfigurationImsSuite.AES128_SHA1))
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
