package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConfigurationImsTimingContractTest
import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationImsTimingDraftTest {
    private val selected = ConfigurationImsTimingContractTest.SELECTED
    private fun instance() = ConfigurationInstance(ConfigurationInstanceId("opaque"), "实例", ConfigurationLifecycle.RUNNING,
        ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01",
        configVersion = 1, configRevision = INITIAL, callSignaling = ConfigurationCallSignaling(), imsTiming = ConfigurationImsTiming())

    @Test fun retentionAndRefreshKeepTheCompleteEditedGroupAndItsRevision() {
        val row = instance().copy(imsTiming = selected)
        val draft = ConfigurationEditorDraft(row)
        val edited = selected.copy(policy = selected.policy!!.copy(registration = selected.policy!!.registration.copy(refreshPercent = 70)))
        assertTrue(draft.replaceImsTiming(edited))
        val fresh = row.copy(configRevision = NEXT, imsTiming = ConfigurationImsTiming(), epdgFqdn = "fresh.example")
        draft.syncFrom(fresh)
        val retained = ConfigurationEditorDraft(fresh, draft.retained())
        assertEquals(edited, retained.overrides().imsTiming)
        assertEquals(INITIAL, retained.overrides().expectedRevision)
        assertEquals("fresh.example", retained.epdgFqdn.text.toString())
        assertEquals(1, retained.modifiedCount)
        retained.discard()
        assertEquals(ConfigurationImsTiming(), retained.imsTiming)
        assertEquals(NEXT, retained.overrides().expectedRevision)
    }

    @Test fun timingRequiresNextStartAndIncompleteInputStaysUnsaved() {
        val draft = ConfigurationEditorDraft(instance())
        draft.replaceCallSignaling(ConfigurationCallSignaling(includePANI = false))
        assertTrue(draft.onlyNextCallChanges)
        draft.replaceImsTiming(selected)
        assertFalse(draft.onlyNextCallChanges)
        assertEquals(2, draft.modifiedCount)
        assertEquals("保存并重启", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
        draft.replaceImsTiming(selected.copy(policy = selected.policy!!.copy(transaction = selected.policy!!.transaction.copy(fMillis = null))))
        draft.submit { _, _, _ -> error("incomplete timer policy must not write") }
        assertEquals(ConfigurationEditStatus.Invalid, draft.status)
        assertNotNull(draft.imsTimingError)
        assertTrue(draft.errors.isEmpty())
        assertEquals("请检查标出的字段；修改尚未保存", configurationEditSummary(draft))
    }

    @Test fun unknownNeedsConfirmedClearAndUnconfirmedSavePreservesDraft() {
        val row = instance()
        val unknown = ConfigurationEditorDraft(row.copy(imsTiming = null))
        assertFalse(unknown.replaceImsTiming(selected))
        assertTrue(unknown.replaceImsTiming(ConfigurationImsTiming()))
        unknown.submit { _, next, complete ->
            assertEquals(ConfigurationImsTiming(), next.imsTiming)
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = NEXT)))
            ConfigurationApplyAcceptance.Accepted
        }
        assertTrue(unknown.replaceImsTiming(selected))
        var finish: (ConfigurationApplyOutcome) -> Unit = {}
        unknown.submit { _, _, callback -> finish = callback; ConfigurationApplyAcceptance.Accepted }
        assertFalse(unknown.replaceImsTiming(ConfigurationImsTiming()))
        finish(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = INITIAL)))
        assertEquals(ConfigurationEditStatus.Unconfirmed, unknown.status)
        assertEquals(selected, unknown.imsTiming)
    }

    companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
