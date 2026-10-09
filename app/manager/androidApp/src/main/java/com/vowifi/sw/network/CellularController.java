package com.vowifi.sw.network;

/** Recovery of legacy module-owned modem restrictions; no new disabling policy. */
final class CellularController {
    interface Store {
        Policy load(String key) throws Exception;
        void save(String key, Policy policy) throws Exception;
        void remove(String key) throws Exception;
    }

    interface Phone {
        Card read(String key) throws Exception;
        boolean setEnabled(int slot, boolean enabled) throws Exception;
    }

    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }

    static final class Policy {
        final int slot;
        final boolean originalEnabled;
        Policy(int slot, boolean originalEnabled) {
            this.slot = slot;
            this.originalEnabled = originalEnabled;
        }
    }

    static final class Card {
        final int slot, subId;
        final boolean modemEnabled, csRegistered, psRegistered, allCallsIdle, stockWfc, systemIms;
        Card(int slot, int subId, boolean modemEnabled, boolean csRegistered, boolean psRegistered,
             boolean allCallsIdle, boolean stockWfc, boolean systemIms) {
            this.slot = slot;
            this.subId = subId;
            this.modemEnabled = modemEnabled;
            this.csRegistered = csRegistered;
            this.psRegistered = psRegistered;
            this.allCallsIdle = allCallsIdle;
            this.stockWfc = stockWfc;
            this.systemIms = systemIms;
        }
    }

    static final class Status {
        final Card card;
        final Policy policy;
        final boolean allowCellular;
        Status(Card card, Policy policy) {
            this.card = card;
            this.policy = policy;
            this.allowCellular = policy == null;
        }
        String effective() {
            if (policy == null) return "system";
            if (policy.slot != card.slot) return "card_moved";
            if (card.modemEnabled) return "drift";
            return card.csRegistered || card.psRegistered ? "pending" : "disabled";
        }
        String json() {
            return "{\"ok\":true,\"allowCellular\":" + allowCellular +
                ",\"modemEnabled\":" + card.modemEnabled + ",\"slot\":" + card.slot +
                ",\"effective\":\"" + effective() + "\",\"stockWfc\":" + card.stockWfc +
                ",\"systemImsRegistered\":" + card.systemIms +
                ",\"wwanCsRegistered\":" + card.csRegistered +
                ",\"wwanPsRegistered\":" + card.psRegistered +
                ",\"callsIdle\":" + card.allCallsIdle + "}";
        }
    }

    private final Store store;
    private final Phone phone;

    CellularController(Store store, Phone phone) { this.store = store; this.phone = phone; }

    Status status(String key) throws Exception {
        return new Status(phone.read(key), store.load(key));
    }

    Status deny(String key) throws Exception {
        throw new Failure("FEATURE_REMOVED");
    }

    Status restore(String key) throws Exception {
        Card before = phone.read(key);
        Policy policy = store.load(key);
        if (policy == null) return new Status(before, null);
        idle(before);
        sameSlot(policy, before);
        if (policy.originalEnabled && !before.modemEnabled) {
            unchanged(before, phone.read(key));
            if (!phone.setEnabled(before.slot, true)) throw new Failure("MODEM_REJECTED");
        }
        Card after = phone.read(key);
        unchanged(before, after);
        if (policy.originalEnabled && !after.modemEnabled) throw new Failure("NOT_CONFIRMED");
        store.remove(key);
        return new Status(after, null);
    }

    private static void idle(Card card) throws Failure {
        if (!card.allCallsIdle) throw new Failure("ACTIVE_CALL");
    }

    private static void sameSlot(Policy policy, Card card) throws Failure {
        if (policy.slot != card.slot) throw new Failure("CARD_MOVED");
    }

    private static void unchanged(Card before, Card after) throws Failure {
        if (before.slot != after.slot || before.subId != after.subId) throw new Failure("CARD_CHANGED");
        idle(after);
    }
}
