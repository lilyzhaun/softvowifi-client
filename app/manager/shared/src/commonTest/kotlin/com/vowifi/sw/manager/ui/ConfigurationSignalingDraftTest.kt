package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationSignalingDraftTest {
    @Test fun theExplicitAtomicGroupSurvivesDraftRetentionDiscardAndUnrelatedEdits() {
        val row = instance().copy(callSignaling = original)
        val draft = ConfigurationEditorDraft(row)
        assertEquals(original, draft.overrides().callSignaling)
        assertFalse(draft.dirty)
        assertTrue(draft.replaceCallSignaling(original.copy(includePANI = false)))
        assertTrue(draft.dirty)
        assertEquals(1, draft.modifiedCount)
        val retained = ConfigurationEditorDraft(row, draft.retained())
        assertEquals(original.copy(includePANI = false), retained.callSignaling)
        retained.discard()
        assertEquals(original, retained.callSignaling)
        retained.epdgFqdn.edit { replace(0, length, "new.epdg.example") }
        assertEquals(original, retained.overrides().callSignaling)
    }

    @Test fun refreshMergesTheGroupAtomicallyAndKeepsTheEditedRevision() {
        val row = instance().copy(callSignaling = original)
        val baseline = ConfigurationDraftValues.from(row)
        val edited = baseline.copy(callSignaling = original.copy(includePANI = false))
        val incoming = baseline.copy(callSignaling = original.copy(addressing = ConfigurationCallAddressing(requestURI = "tel")), epdgFqdn = "fresh.example")
        assertEquals(edited.callSignaling, synchronizedConfigurationDraft(edited, baseline, incoming).callSignaling)
        assertEquals("fresh.example", synchronizedConfigurationDraft(edited, baseline, incoming).epdgFqdn)
        assertEquals(incoming.callSignaling, synchronizedConfigurationDraft(baseline, baseline, incoming).callSignaling)
        val draft = ConfigurationEditorDraft(row)
        draft.replaceCallSignaling(edited.callSignaling!!)
        draft.syncFrom(row.copy(callSignaling = incoming.callSignaling, configRevision = NEXT))
        assertEquals(INITIAL, draft.overrides().expectedRevision)
        assertEquals(edited.callSignaling, draft.callSignaling)
        draft.discard()
        assertEquals(NEXT, draft.overrides().expectedRevision)
        assertEquals(incoming.callSignaling, draft.callSignaling)
    }

    @Test fun invalidSignalingCannotSubmitOrPolluteTheOldFieldErrors() {
        val draft = ConfigurationEditorDraft(instance())
        draft.replaceCallSignaling(ConfigurationCallSignaling(sessionTimer = ConfigurationSessionTimer(enabled = false, requirePeer = true)))
        assertFalse(draft.validate())
        assertTrue(draft.errors.isEmpty())
        assertNotNull(draft.signalingError)
        var writes = 0
        draft.submit { _, _, _ -> writes++; ConfigurationApplyAcceptance.Accepted }
        assertEquals(0, writes)
        draft.replaceCallSignaling(ConfigurationCallSignaling(includePANI = false))
        assertTrue(draft.validate())
        assertNull(draft.signalingError)
    }

    @Test fun pendingAndUnconfirmedCallbacksCannotReplaceTheSubmittedPolicy() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        val desired = ConfigurationCallSignaling(includePANI = false)
        draft.replaceCallSignaling(desired)
        var callback: (ConfigurationApplyOutcome) -> Unit = {}
        draft.submit { _, payload, complete ->
            assertEquals(desired, payload.callSignaling)
            callback = complete
            ConfigurationApplyAcceptance.Accepted
        }
        assertTrue(draft.pending)
        assertFalse(draft.replaceCallSignaling(original))
        callback(ConfigurationApplyOutcome.Confirmed(row.copy(callSignaling = original, configRevision = NEXT)))
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals(desired, draft.callSignaling)
        draft.submit { _, _, complete -> callback = complete; ConfigurationApplyAcceptance.Accepted }
        callback(ConfigurationApplyOutcome.Confirmed(row.copy(callSignaling = desired, configRevision = NEXT), effect = ConfigurationApplyEffect.NextCall))
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertFalse(draft.dirty)
        assertEquals(NEXT, draft.overrides().expectedRevision)
        assertTrue(configurationSaveBarDetail(draft, ConfigurationLifecycle.RUNNING).contains("下次呼叫"))
    }

    @Test fun unknownOrPrivateCoverageRequiresAConfirmedWholeGroupClearBeforeEditing() {
        val row = instance().copy(callSignaling = null)
        val draft = ConfigurationEditorDraft(row)
        assertFalse(draft.replaceCallSignaling(original))
        assertNull(draft.callSignaling)
        assertTrue(draft.replaceCallSignaling(ConfigurationCallSignaling()))
        assertFalse(draft.replaceCallSignaling(original))
        draft.submit { _, payload, complete ->
            assertEquals(ConfigurationCallSignaling(), payload.callSignaling)
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(callSignaling = ConfigurationCallSignaling(), configRevision = NEXT), effect = ConfigurationApplyEffect.NextCall))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertTrue(draft.replaceCallSignaling(original))
    }

    @Test fun onlyRunningSignalingChangesAvoidTheRestartConsequence() {
        val draft = ConfigurationEditorDraft(instance())
        draft.replaceCallSignaling(original)
        assertFalse(configurationSaveRestarts(ConfigurationLifecycle.RUNNING, draft))
        assertEquals("保存配置", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
        assertTrue(configurationSaveBarDetail(draft, ConfigurationLifecycle.RUNNING).contains("下次呼叫"))
        assertTrue(configurationLeaveSummary(draft.modifiedCount, ConfigurationLifecycle.RUNNING, draft).contains("下次呼叫"))
        draft.epdgFqdn.edit { replace(0, length, "new.example") }
        assertTrue(configurationSaveRestarts(ConfigurationLifecycle.RUNNING, draft))
        assertEquals("保存并重启", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
        assertTrue(configurationSaveBarDetail(draft, ConfigurationLifecycle.STOPPED).contains("下次启动"))
        assertTrue(configurationSaveRestarts(ConfigurationLifecycle.STARTING, draft))
    }

    private val original = ConfigurationCallSignaling(
        addressing = ConfigurationCallAddressing(requestURI = "sip", phoneContext = "context.example"), includePANI = true,
    )
    private fun instance() = ConfigurationInstance(
        ConfigurationInstanceId("sim-1"), "实例 ••••", ConfigurationLifecycle.RUNNING, ConfigurationTransport.WIFI,
        "", "", "", "", "epdg.example", "ims.example", "001", "01",
        configVersion = 1, configRevision = INITIAL, callSignaling = ConfigurationCallSignaling(),
    )
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
