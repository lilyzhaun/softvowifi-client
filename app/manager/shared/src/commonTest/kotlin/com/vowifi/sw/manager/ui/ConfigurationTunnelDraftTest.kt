package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationTunnelDraftTest {
    @Test fun loadedDraftRefreshAndRetentionKeepTheAtomicGroupAndEditedRevision() {
        val row = instance().copy(tunnelSecurity = selected)
        val draft = ConfigurationEditorDraft(row)
        assertEquals(selected, draft.overrides().tunnelSecurity)
        assertFalse(draft.dirty)
        val changed = selected.copy(policy = selected.policy!!.copy(childSuites = listOf(ConfigurationChildSuite.GCM256)))
        assertTrue(draft.replaceTunnelSecurity(changed))
        draft.syncFrom(row.copy(tunnelSecurity = ConfigurationTunnelSecurity(), configRevision = NEXT, epdgFqdn = "fresh.example"))
        assertEquals(changed, draft.tunnelSecurity)
        assertEquals(INITIAL, draft.overrides().expectedRevision)
        assertEquals("fresh.example", draft.epdgFqdn.text.toString())
        val retained = ConfigurationEditorDraft(row.copy(tunnelSecurity = ConfigurationTunnelSecurity(), configRevision = NEXT, epdgFqdn = "fresh.example"), draft.retained())
        assertEquals(changed, retained.overrides().tunnelSecurity)
        assertEquals(1, retained.modifiedCount)
        retained.discard()
        assertEquals(ConfigurationTunnelSecurity(), retained.tunnelSecurity)
        assertEquals(NEXT, retained.overrides().expectedRevision)
        assertFalse(retained.dirty)
    }

    @Test fun tunnelAndMixedCallEditsRequireRestartAndCannotMasqueradeAsNextCall() {
        val draft = ConfigurationEditorDraft(instance())
        assertTrue(draft.replaceTunnelSecurity(selected))
        assertFalse(draft.onlyNextCallChanges)
        assertTrue(configurationSaveRestarts(ConfigurationLifecycle.RUNNING, draft))
        assertEquals("保存并重启", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
        draft.replaceCallSignaling(ConfigurationCallSignaling(includePANI = false))
        draft.replaceCallMedia(ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB, ConfigurationSpeechFraming.OA))))
        assertFalse(draft.onlySignalingChanges)
        assertFalse(draft.onlyNextCallChanges)
        assertEquals(3, draft.modifiedCount)
        assertTrue(configurationSaveRestarts(ConfigurationLifecycle.RUNNING, draft))
        assertFalse(configurationSaveRestarts(ConfigurationLifecycle.STOPPED, draft))
    }

    @Test fun incompleteGroupsNeverSaveAndUnknownGroupsNeedAnAcknowledgedClear() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(ConfigurationTunnelPolicy()))
        draft.submit { _, _, _ -> error("incomplete tunnel policy must not write") }
        assertEquals(ConfigurationEditStatus.Invalid, draft.status)
        assertNotNull(draft.tunnelError)
        assertTrue(draft.errors.isEmpty())
        assertEquals("请检查标出的字段；修改尚未保存", configurationEditSummary(draft))
        assertEquals(configurationEditSummary(draft), configurationSaveBarDetail(draft, ConfigurationLifecycle.RUNNING))
        draft.replaceTunnelSecurity(selected)
        assertTrue(draft.validate())
        val unknown = ConfigurationEditorDraft(row.copy(tunnelSecurity = null))
        assertFalse(unknown.replaceTunnelSecurity(selected))
        assertTrue(unknown.replaceTunnelSecurity(ConfigurationTunnelSecurity()))
        assertFalse(unknown.replaceTunnelSecurity(selected))
        unknown.submit { _, payload, complete ->
            assertEquals(ConfigurationTunnelSecurity(), payload.tunnelSecurity)
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = NEXT)))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, unknown.status)
        assertTrue(unknown.replaceTunnelSecurity(selected))
    }

    @Test fun pendingAndWrongReadbackNeverOverwriteDraftOrClaimAConfirmedSave() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        draft.replaceTunnelSecurity(selected)
        var complete: (ConfigurationApplyOutcome) -> Unit = {}
        draft.submit { _, next, callback ->
            assertEquals(selected, next.tunnelSecurity)
            complete = callback
            ConfigurationApplyAcceptance.Accepted
        }
        assertFalse(draft.replaceTunnelSecurity(ConfigurationTunnelSecurity()))
        complete(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = NEXT)))
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals(selected, draft.tunnelSecurity)
        assertEquals(INITIAL, draft.overrides().expectedRevision)
    }

    private fun instance() = ConfigurationInstance(ConfigurationInstanceId("sim-1"), "实例", ConfigurationLifecycle.RUNNING,
        ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01",
        configVersion = 1, configRevision = INITIAL, callSignaling = ConfigurationCallSignaling(), callMedia = ConfigurationCallMedia(),
        tunnelSecurity = ConfigurationTunnelSecurity())
    private val selected = ConfigurationTunnelSecurity(ConfigurationTunnelPolicy(listOf(ConfigurationIkeSuite.CBC256_SHA256_DH14), listOf(ConfigurationChildSuite.GCM128)))
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
