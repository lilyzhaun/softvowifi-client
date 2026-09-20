package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationEditSessionTest {
    @Test fun backendUnchangedNeedsMatchingAuthoritativeReadback() {
        for (matches in listOf(false, true)) {
            val draft = ConfigurationEditorDraft(instance())
            draft.dns.edit { append("verbatim.invalid ") }
            draft.submit { _, _, complete ->
                complete(ConfigurationApplyOutcome.Confirmed(
                    instance().copy(dns = if (matches) "verbatim.invalid " else "verbatim.invalid"),
                    ConfigurationWriteResult.Unchanged,
                ))
                ConfigurationApplyAcceptance.Accepted
            }
            assertEquals(!matches, draft.dirty)
            assertEquals(if (matches) ConfigurationEditStatus.Unchanged else ConfigurationEditStatus.Unconfirmed, draft.status)
        }
    }
    @Test fun correctingInvalidFieldClearsErrorWithoutAnotherSave() {
        val draft = ConfigurationEditorDraft(instance())
        draft.smsc.edit { append("invalid") }
        draft.validate()
        draft.smsc.edit { replace(0, length, "+123") }
        assertTrue(draft.errors.isEmpty())
        assertFalse(configurationEditSummary(draft).contains("请检查"))
    }
    private val owner = SimOwnerId.fromHmacSha256(ByteArray(32) { 1 })
    private val otherOwner = SimOwnerId.fromHmacSha256(ByteArray(32) { 2 })

    @Test fun sameOwnerRebindsNewHandleButReusedHandleNeverBindsOtherOwner() {
        val memory = ConfigurationDraftMemory()
        val first = ConfigurationDraftStore(memory)
        first.draftFor(instance()).dns.edit { append("user.invalid") }
        first.retain()
        val next = ConfigurationDraftStore(memory)
        assertEquals("user.invalid", next.draftFor(instance("sim-2")).dns.text.toString())
        assertEquals("", next.draftFor(instance().copy(draftOwnerId = otherOwner)).dns.text.toString())
    }

    @Test fun missingOwnerIsSourceScopedAndNewProcessStartsFromDisk() {
        val memory = ConfigurationDraftMemory()
        val first = ConfigurationDraftStore(memory)
        val row = instance().copy(draftOwnerId = null)
        first.draftFor(row).dns.edit { append("user.invalid") }
        assertSame(first.draftFor(row), first.draftFor(row))
        first.retain()
        assertEquals("", ConfigurationDraftStore(memory).draftFor(row).dns.text.toString())
        assertEquals("", ConfigurationDraftStore(ConfigurationDraftMemory()).draftFor(instance()).dns.text.toString())
    }

    @Test fun discardUsesNewestBaselineAndNoopNeverApplies() {
        val draft = ConfigurationEditorDraft(instance())
        var calls = 0
        val apply: ConfigurationSubmit = { _, _, _ -> calls++; ConfigurationApplyAcceptance.Accepted }
        draft.submit(apply)
        assertEquals(ConfigurationEditStatus.Unchanged, draft.status)
        draft.dns.edit { append("user.invalid") }
        draft.syncFrom(instance().copy(dns = "new.invalid", smsc = "+123"))
        assertEquals("user.invalid", draft.dns.text.toString())
        assertEquals("+123", draft.smsc.text.toString())
        draft.discard()
        assertEquals("new.invalid", draft.dns.text.toString())
        assertFalse(draft.dirty)
        assertEquals(0, calls)
    }

    @Test fun invalidSmscIsInlineAndNeverCallsSource() {
        val draft = ConfigurationEditorDraft(instance())
        draft.smsc.edit { append("invalid synthetic input") }
        draft.submit { _, _, _ -> fail("invalid input reached backend") }
        assertEquals("SMSC 须以 + 开头，或留空", draft.errors[ConfigurationFieldKey.Smsc])
        assertEquals(ConfigurationEditStatus.Invalid, draft.status)
    }

    @Test fun busyRejectDoesNotFreezeAndAcceptedDuplicateIsBlocked() {
        val draft = ConfigurationEditorDraft(instance())
        draft.dns.edit { append("user.invalid") }
        draft.submit { _, _, _ -> ConfigurationApplyAcceptance.Busy }
        assertEquals(ConfigurationEditStatus.BusyRejected, draft.status)
        assertFalse(draft.pending)
        var calls = 0
        val apply: ConfigurationSubmit = { _, _, _ -> calls++; ConfigurationApplyAcceptance.Accepted }
        draft.submit(apply)
        draft.submit(apply)
        draft.discard()
        assertTrue(draft.pending)
        assertEquals(1, calls)
        assertEquals("user.invalid", draft.dns.text.toString())
    }

    @Test fun confirmedReadbackAloneClearsDraftAndFailuresKeepIt() {
        for (result in listOf(ConfigurationApplyOutcome.Failed, ConfigurationApplyOutcome.Unconfirmed)) {
            val draft = ConfigurationEditorDraft(instance())
            draft.dns.edit { append("user.invalid") }
            draft.submit { _, _, complete -> complete(result); ConfigurationApplyAcceptance.Accepted }
            assertTrue(draft.dirty)
            assertFalse(draft.pending)
        }
        val draft = ConfigurationEditorDraft(instance())
        draft.dns.edit { append("user.invalid") }
        draft.submit { _, _, complete ->
            complete(ConfigurationApplyOutcome.Confirmed(instance().copy(dns = "user.invalid")))
            ConfigurationApplyAcceptance.Accepted
        }
        assertFalse(draft.dirty)
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
    }

    @Test fun recreationPendingIsUnconfirmedAndNeverReplayedOrClearsAnotherSim() {
        val memory = ConfigurationDraftMemory()
        val store = ConfigurationDraftStore(memory)
        val draft = store.draftFor(instance())
        draft.dns.edit { append("user.invalid") }
        var completion: (ConfigurationApplyOutcome) -> Unit = {}
        draft.submit { _, _, complete -> completion = complete; ConfigurationApplyAcceptance.Accepted }
        store.retain()
        val restored = ConfigurationDraftStore(memory).draftFor(instance("sim-9"))
        assertEquals(ConfigurationEditStatus.Unconfirmed, restored.status)
        assertFalse(restored.pending)
        completion(ConfigurationApplyOutcome.Confirmed(instance().copy(draftOwnerId = otherOwner, dns = "user.invalid")))
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertTrue(restored.dirty)
    }

    private fun instance(handle: String = "sim-1") = ConfigurationInstance(
        ConfigurationInstanceId(handle), "实例 ••••", ConfigurationLifecycle.STOPPED,
        ConfigurationTransport.AUTO, "", "", "", "", "epdg.invalid", "ims.invalid", "001", "01",
        draftOwnerId = owner,
    )
}
