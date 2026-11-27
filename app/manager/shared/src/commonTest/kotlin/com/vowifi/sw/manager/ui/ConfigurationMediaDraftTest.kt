package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationMediaDraftTest {
    @Test fun theActualDraftAndRetainedDraftPreserveTheCompleteExplicitMediaGroup() {
        val row = instance().copy(callMedia = selected)
        val draft = ConfigurationEditorDraft(row)
        assertEquals(selected, draft.overrides().callMedia)
        assertFalse(draft.dirty)
        val retained = ConfigurationEditorDraft(row, draft.retained())
        assertEquals(selected, retained.overrides().callMedia)
        retained.epdgFqdn.edit { replace(0, length, "new.example") }
        assertEquals(selected, retained.overrides().callMedia)
    }

    @Test fun aCleanDraftFollowsFreshMediaButRetainsAnUnknownGroupAsUnknown() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        draft.syncFrom(row.copy(callMedia = selected, configRevision = NEXT))
        assertEquals(selected, draft.overrides().callMedia)
        assertEquals(NEXT, draft.overrides().expectedRevision)
        draft.syncFrom(row.copy(callMedia = null, configRevision = INITIAL))
        assertNull(draft.overrides().callMedia)
        assertFalse(draft.dirty)
    }

    @Test fun unrelatedEditsCannotEraseMediaFromTheActualSubmittedPayloadOrAcknowledgedBaseline() {
        val row = instance().copy(callMedia = selected)
        val draft = ConfigurationEditorDraft(row)
        draft.epdgFqdn.edit { replace(0, length, "new.example") }
        draft.submit { _, payload, complete ->
            assertEquals(selected, payload.callMedia)
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(epdgFqdn = "new.example", configRevision = NEXT)))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertEquals(selected, draft.overrides().callMedia)
    }

    @Test fun mediaRefreshAndRetentionKeepAnEditedAtomicGroupAndItsOldRevision() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        assertTrue(draft.replaceCallMedia(selected))
        val fresh = selected.copy(speechFormats = selected.speechFormats.map { it.copy(initialMode = 2) })
        draft.syncFrom(row.copy(callMedia = fresh, epdgFqdn = "fresh.example", configRevision = NEXT))
        assertEquals(selected, draft.callMedia)
        assertEquals("fresh.example", draft.epdgFqdn.text.toString())
        assertEquals(INITIAL, draft.overrides().expectedRevision)
        val retained = ConfigurationEditorDraft(row.copy(callMedia = fresh, epdgFqdn = "fresh.example", configRevision = NEXT), draft.retained())
        assertEquals(selected, retained.callMedia)
        assertEquals(INITIAL, retained.overrides().expectedRevision)
        assertEquals(1, retained.modifiedCount)
        retained.discard()
        assertEquals(fresh, retained.callMedia)
        assertEquals(NEXT, retained.overrides().expectedRevision)
        assertFalse(retained.dirty)
    }

    @Test fun mediaAndSignalingOnlyUseTheNextCallConsequenceButConnectionEditsStillRestart() {
        val draft = ConfigurationEditorDraft(instance())
        draft.replaceCallMedia(selected)
        assertTrue(draft.onlyNextCallChanges)
        assertFalse(draft.onlySignalingChanges)
        assertFalse(configurationSaveRestarts(ConfigurationLifecycle.RUNNING, draft))
        assertEquals("保存配置", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
        assertTrue(configurationSaveBarDetail(draft, ConfigurationLifecycle.RUNNING).contains("下次呼叫"))
        draft.replaceCallSignaling(ConfigurationCallSignaling(includePANI = false))
        assertEquals(2, draft.modifiedCount)
        assertFalse(configurationSaveRestarts(ConfigurationLifecycle.RUNNING, draft))
        assertTrue(configurationLeaveSummary(draft.modifiedCount, ConfigurationLifecycle.RUNNING, draft).contains("下次呼叫"))
        assertTrue(configurationSaveRestarts(ConfigurationLifecycle.STARTING, draft))
        draft.epdgFqdn.edit { replace(0, length, "new.example") }
        assertTrue(configurationSaveRestarts(ConfigurationLifecycle.RUNNING, draft))
        assertEquals("保存并重启", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
    }

    @Test fun unknownMediaNeedsAnAcknowledgedWholeGroupClearBeforeIndividualEdits() {
        val row = instance().copy(callMedia = null)
        val draft = ConfigurationEditorDraft(row)
        assertFalse(draft.replaceCallMedia(selected))
        assertNull(draft.callMedia)
        assertTrue(draft.replaceCallMedia(ConfigurationCallMedia()))
        assertFalse(draft.replaceCallMedia(selected))
        draft.submit { _, payload, complete ->
            assertEquals(ConfigurationCallMedia(), payload.callMedia)
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(callMedia = ConfigurationCallMedia(), configRevision = NEXT),
                effect = ConfigurationApplyEffect.NextCall))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertTrue(draft.replaceCallMedia(selected))
    }

    @Test fun invalidMediaNeverSubmitsAndPendingOrWrongAcknowledgmentsCannotReplaceTheDraft() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        draft.replaceCallMedia(selected.copy(speechFormats = selected.speechFormats + selected.speechFormats))
        var writes = 0
        draft.submit { _, _, _ -> writes++; ConfigurationApplyAcceptance.Accepted }
        assertEquals(0, writes)
        assertNotNull(draft.mediaError)
        assertTrue(draft.errors.isEmpty())
        assertTrue(configurationEditSummary(draft).contains("请检查"))
        draft.replaceCallMedia(selected)
        assertTrue(draft.validate())
        assertNull(draft.mediaError)
        var callback: (ConfigurationApplyOutcome) -> Unit = {}
        draft.submit { _, payload, complete ->
            assertEquals(selected, payload.callMedia)
            callback = complete
            ConfigurationApplyAcceptance.Accepted
        }
        assertFalse(draft.replaceCallMedia(ConfigurationCallMedia()))
        callback(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = NEXT), effect = ConfigurationApplyEffect.NextCall))
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals(selected, draft.callMedia)
        assertEquals(INITIAL, draft.overrides().expectedRevision)
        draft.submit { _, _, complete -> callback = complete; ConfigurationApplyAcceptance.Accepted }
        callback(ConfigurationApplyOutcome.Confirmed(row.copy(callMedia = selected, configRevision = NEXT), effect = ConfigurationApplyEffect.NextCall))
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertFalse(draft.dirty)
        assertEquals(NEXT, draft.overrides().expectedRevision)
        assertTrue(configurationLeaveSummary(1, ConfigurationLifecycle.RUNNING, draft).contains("下次呼叫"))
    }

    private val selected = ConfigurationCallMedia(listOf(ConfigurationSpeechFormat(ConfigurationSpeechCodec.AMR_NB,
        ConfigurationSpeechFraming.OA, listOf(0, 2, 4), initialMode = 0, payloadType = 110)))
    private fun instance() = ConfigurationInstance(
        ConfigurationInstanceId("sim-1"), "实例 ••••", ConfigurationLifecycle.RUNNING, ConfigurationTransport.WIFI,
        "", "", "", "", "epdg.example", "ims.example", "001", "01",
        configVersion = 1, configRevision = INITIAL, callSignaling = ConfigurationCallSignaling(), callMedia = ConfigurationCallMedia(),
    )
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
