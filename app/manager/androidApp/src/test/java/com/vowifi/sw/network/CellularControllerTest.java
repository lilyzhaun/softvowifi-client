package com.vowifi.sw.network;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class CellularControllerTest {
    private static final String CARD = "0123456789";
    private final MemoryStore store = new MemoryStore();
    private final FakePhone phone = new FakePhone();
    private final CellularController controller = new CellularController(store, phone);

    @Test public void retiredDisableCannotReadHardwareOrCreatePolicy() throws Exception {
        expectCode("FEATURE_REMOVED", () -> controller.deny(CARD));
        assertEquals(0, phone.reads);
        assertEquals(0, phone.writes);
        assertTrue(store.values.isEmpty());
        assertTrue(phone.enabled);
    }

    @Test public void legacyRecoveryRestoresOnlyRecordedTarget() throws Exception {
        savedRestriction(true);
        phone.beforeWrite = () -> assertTrue(store.values.get(CARD).originalEnabled);
        CellularController.Status result = controller.restore(CARD);
        assertTrue(result.allowCellular);
        assertEquals("system", result.effective());
        assertTrue(phone.enabled);
        assertEquals(1, phone.writes);
        assertEquals(0, phone.lastWrittenSlot);
    }

    @Test public void retiredDisableNeverReplacesSavedOriginal() throws Exception {
        savedRestriction(true);
        expectCode("FEATURE_REMOVED", () -> controller.deny(CARD));
        expectCode("FEATURE_REMOVED", () -> controller.deny(CARD));
        assertTrue(store.values.get(CARD).originalEnabled);
        assertEquals(0, phone.writes);
        controller.restore(CARD);
        assertTrue(phone.enabled);
        assertTrue(store.values.isEmpty());
    }

    @Test public void restoringOriginallyDisabledCardDoesNotEnableIt() throws Exception {
        savedRestriction(false);
        CellularController.Status result = controller.restore(CARD);
        assertTrue(result.allowCellular);
        assertFalse(result.card.modemEnabled);
        assertEquals(0, phone.writes);
        assertTrue(store.values.isEmpty());
    }

    @Test public void newControllerReadsSavedPolicyWithoutReapplying() throws Exception {
        savedRestriction(true);
        CellularController fresh = new CellularController(store, phone);
        assertFalse(fresh.status(CARD).allowCellular);
        assertEquals("disabled", fresh.status(CARD).effective());
        assertEquals(0, phone.writes);
    }

    @Test public void driftIsReportedInsteadOfPollingDisableOrPretendingSuccess() throws Exception {
        savedRestriction(true);
        phone.enabled = true;
        assertEquals("drift", controller.status(CARD).effective());
        assertFalse(controller.status(CARD).allowCellular);
        assertTrue(controller.status(CARD).card.modemEnabled);
        assertEquals(0, phone.writes);
    }

    @Test public void noCellularRegistrationIsNotEquivalentToModemDisabled() throws Exception {
        phone.enabled = true;
        assertEquals("system", controller.status(CARD).effective());
        assertTrue(controller.status(CARD).card.modemEnabled);
        assertEquals(0, phone.writes);
    }

    @Test public void legacyStatusDoesNotWriteWhileRegistrationRemains() throws Exception {
        savedRestriction(true);
        phone.csRegistered = true;
        phone.detachImmediately = false;
        assertEquals("pending", controller.status(CARD).effective());
        assertEquals(0, phone.writes);
    }

    @Test public void movedCardCannotRestoreOrApplyToDifferentSlot() throws Exception {
        savedRestriction(true);
        phone.slot = 1;
        expectCode("CARD_MOVED", () -> controller.restore(CARD));
        expectCode("FEATURE_REMOVED", () -> controller.deny(CARD));
        assertEquals("card_moved", controller.status(CARD).effective());
        assertEquals(0, phone.writes);
        assertFalse(phone.enabled);
    }

    @Test public void activeCallBlocksPolicyAndHardwareWrites() throws Exception {
        phone.idle = false;
        savedRestriction(true);
        expectCode("ACTIVE_CALL", () -> controller.restore(CARD));
        assertFalse(store.values.isEmpty());
        assertEquals(0, phone.writes);
    }

    @Test public void missingCardDoesNotOperateOnRememberedSlot() throws Exception {
        savedRestriction(true);
        phone.present = false;
        expectCode("SIM_NOT_ACTIVE", () -> controller.restore(CARD));
        assertEquals(0, phone.writes);
        assertFalse(store.values.isEmpty());
    }

    @Test public void rejectedWriteKeepsRecoveryRecordAndIsNotSuccessful() throws Exception {
        phone.accept = false;
        savedRestriction(true);
        expectCode("MODEM_REJECTED", () -> controller.restore(CARD));
        assertTrue(store.values.get(CARD).originalEnabled);
        assertEquals("disabled", controller.status(CARD).effective());
        phone.enabled = true;
        controller.restore(CARD);
        assertTrue(store.values.isEmpty());
        assertEquals(1, phone.writes);
    }

    @Test public void storeFailureMustPrecedeAnyModemWrite() throws Exception {
        store.failSave = true;
        expectCode("FEATURE_REMOVED", () -> controller.deny(CARD));
        assertEquals(0, phone.writes);
        assertTrue(phone.enabled);
    }

    @Test public void identityChangeBeforeBinderWriteIsRejected() throws Exception {
        phone.changeOnSecondRead = true;
        savedRestriction(true);
        expectCode("CARD_CHANGED", () -> controller.restore(CARD));
        assertEquals(0, phone.writes);
    }

    private interface Operation { void run() throws Exception; }

    private void savedRestriction(boolean originalEnabled) {
        store.values.put(CARD, new CellularController.Policy(phone.slot, originalEnabled));
        phone.enabled = false;
    }

    @Test public void retiredRecoveryNeverDisablesCurrentUserEnabledState() throws Exception {
        savedRestriction(false);
        phone.enabled = true;
        controller.restore(CARD);
        assertTrue(phone.enabled);
        assertEquals(0, phone.writes);
        assertTrue(store.values.isEmpty());
    }
    private static void expectCode(String code, Operation operation) throws Exception {
        try { operation.run(); fail("Expected " + code); }
        catch (CellularController.Failure error) { assertEquals(code, error.code); }
    }

    private static final class MemoryStore implements CellularController.Store {
        final Map<String, CellularController.Policy> values = new HashMap<>();
        boolean failSave;
        public CellularController.Policy load(String key) { return values.get(key); }
        public void save(String key, CellularController.Policy policy) throws CellularController.Failure {
            if (failSave) throw new CellularController.Failure("STORE_FAILED");
            values.put(key, policy);
        }
        public void remove(String key) { values.remove(key); }
    }

    private static final class FakePhone implements CellularController.Phone {
        boolean enabled = true, present = true, idle = true, accept = true;
        boolean csRegistered, detachImmediately = true, changeOnSecondRead;
        int slot, writes, reads, lastWrittenSlot = -1;
        Runnable beforeWrite = () -> {};
        public CellularController.Card read(String key) throws CellularController.Failure {
            if (!present) throw new CellularController.Failure("SIM_NOT_ACTIVE");
            reads++;
            return new CellularController.Card(slot, changeOnSecondRead && reads > 1 ? 99 : 52,
                enabled, csRegistered, false, idle, false, false);
        }
        public boolean setEnabled(int slot, boolean value) {
            beforeWrite.run(); writes++; lastWrittenSlot = slot;
            if (accept) { enabled = value; if (detachImmediately) csRegistered = false; }
            return accept;
        }
    }
}
