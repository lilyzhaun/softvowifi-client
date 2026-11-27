package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationSwuAccessDraftTest {
    @Test fun innerProtocolOnlyEditRemainsNextStartAndKeepsRevisionBoundDraft() {
        val row = instance().copy(swuAccess = ConfigurationSwuAccess(ConfigurationSwuAccessPolicy("ims", 1358)))
        val draft = ConfigurationEditorDraft(row)
        val inner = row.swuAccess!!.policy!!.copy(innerProtocol = ConfigurationSwuInnerProtocol("ipv4", "ipv6"))
        assertTrue(draft.replaceSwuAccess(ConfigurationSwuAccess(inner)))
        assertTrue(draft.swuAccessModified)
        assertFalse(draft.onlyNextCallChanges)
        assertTrue(configurationSaveRestarts(row.lifecycle, draft))
        draft.syncFrom(row.copy(configRevision = NEXT))
        assertEquals(inner, draft.swuAccess!!.policy)
        assertEquals(INITIAL, draft.overrides().expectedRevision)
        draft.discard()
        assertNull(draft.swuAccess!!.policy!!.innerProtocol)
        assertEquals(NEXT, draft.overrides().expectedRevision)
    }

    private val selected = ConfigurationSwuAccess(ConfigurationSwuAccessPolicy("voice.example.test", 1340))
    private fun instance() = ConfigurationInstance(ConfigurationInstanceId("opaque"), "instance", ConfigurationLifecycle.RUNNING,
        ConfigurationTransport.WIFI, "", "", "", "", "", "", "001", "01", configVersion = 1,
        configRevision = INITIAL, callSignaling = ConfigurationCallSignaling(), swuAccess = ConfigurationSwuAccess())

    @Test fun mixedAccessChangeCannotUseTheNextCallHotPathAndRefreshKeepsTheWholeDraft() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        assertTrue(draft.replaceCallSignaling(ConfigurationCallSignaling(includePANI = false)))
        assertTrue(draft.onlyNextCallChanges)
        assertTrue(draft.replaceSwuAccess(selected))
        assertFalse(draft.onlyNextCallChanges)
        assertFalse(draft.onlySignalingChanges)
        assertTrue(configurationSaveRestarts(row.lifecycle, draft))
        assertEquals(selected, draft.overrides().swuAccess)
        val incoming = row.copy(configRevision = NEXT,
            swuAccess = ConfigurationSwuAccess(ConfigurationSwuAccessPolicy("fresh.example.test", 1400)))
        draft.syncFrom(incoming)
        assertEquals(selected, draft.swuAccess)
        assertEquals(INITIAL, draft.overrides().expectedRevision)
        draft.discard()
        assertEquals(incoming.swuAccess, draft.swuAccess)
        assertEquals(NEXT, draft.overrides().expectedRevision)
        assertFalse(draft.dirty)
    }

    @Test fun unknownAccessRequiresConfirmedClearAndMismatchedReadbackCannotClaimSaved() {
        val unknown = ConfigurationEditorDraft(instance().copy(swuAccess = null))
        assertFalse(unknown.replaceSwuAccess(selected))
        assertTrue(unknown.replaceSwuAccess(ConfigurationSwuAccess()))
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        assertTrue(draft.replaceSwuAccess(selected))
        draft.submit { _, _, complete ->
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = NEXT)))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals(selected, draft.swuAccess)
        assertTrue(draft.dirty)
    }

    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
