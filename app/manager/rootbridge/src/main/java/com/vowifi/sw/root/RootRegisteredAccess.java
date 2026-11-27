package com.vowifi.sw.root;

import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private, owner-bound registration observations. No framework objects or errors are serialized. */
final class RootRegisteredAccess {
    @FunctionalInterface interface StateReader { Object read(int slot) throws Exception; }
    @FunctionalInterface interface EquivalentHomeReader { Object read(int subId) throws Exception; }
    private record Owner(int slot, int subId, int port, String iccid) {
        @Override public String toString() { return "RegisteredAccessOwner"; }
    }
    private record Registration(String plmn, boolean roaming) {}

    static JSONObject read(Callable<List<SubscriptionInfo>> subscriptions, StateReader states,
            EquivalentHomeReader equivalentHomes, LongSupplier clock) {
        long observed = clock.getAsLong();
        try {
            List<Owner> before = owners(subscriptions.call());
            if (before == null) return null;
            JSONArray cards = new JSONArray();
            for (Owner owner : before) {
                Registration registration;
                try { registration = registration(states.read(owner.slot())); }
                catch (Exception ignored) { registration = null; }
                JSONObject card = new JSONObject().put("slot", owner.slot()).put("subId", owner.subId())
                    .put("port", owner.port()).put("iccid", owner.iccid())
                    .put("state", registration == null ? "unknown" : "known");
                if (registration != null) card.put("plmn", registration.plmn()).put("roaming", registration.roaming());
                // The Phone's cached EF is subscription-bound even without WWAN registration.
                // Empty means its returned list had no usable first entry; failure stays absent.
                try {
                    Object result = equivalentHomes.read(owner.subId());
                    if (result instanceof List<?> list) {
                        if (list.isEmpty()) card.put("firstEhplmn", "");
                        else if (list.get(0) instanceof String first)
                            card.put("firstEhplmn", first.matches("[0-9]{5,6}") ? first : "");
                    }
                } catch (Exception ignored) { /* Unobserved; do not substitute the subscription database. */ }
                cards.put(card);
            }
            // Copy values before reading: SubscriptionInfo objects themselves can change.
            if (!before.equals(owners(subscriptions.call()))) return null;
            return new JSONObject().put("schema", 1).put("observedMs", observed).put("cards", cards);
        } catch (Exception ignored) { return null; }
    }

    private static List<Owner> owners(List<SubscriptionInfo> active) {
        if (active == null || active.size() > 2) return null;
        ArrayList<Owner> owners = new ArrayList<>();
        HashSet<Integer> slots = new HashSet<>(), subscriptions = new HashSet<>();
        HashSet<String> identities = new HashSet<>();
        for (SubscriptionInfo info : active) {
            if (info == null) return null;
            int slot = info.getSimSlotIndex(), subId = info.getSubscriptionId(), port = info.getPortIndex();
            String iccid = info.getIccId();
            if (iccid != null) iccid = iccid.replaceFirst("[Ff]+$", "");
            if (slot < 0 || slot > 1 || subId < 0 || port < 0 || iccid == null || !iccid.matches("[0-9]{15,22}") ||
                !slots.add(slot) || !subscriptions.add(subId) || !identities.add(iccid)) return null;
            owners.add(new Owner(slot, subId, port, iccid));
        }
        owners.sort(Comparator.comparingInt(Owner::slot));
        return owners;
    }

    private static Registration registration(Object state) throws ReflectiveOperationException {
        if (!(state instanceof ServiceState)) return null;
        Class<?> network = Class.forName("android.telephony.NetworkRegistrationInfo");
        int wwan = Class.forName("android.telephony.AccessNetworkConstants").getField("TRANSPORT_TYPE_WWAN").getInt(null);
        int home = network.getField("REGISTRATION_STATE_HOME").getInt(null);
        int roaming = network.getField("REGISTRATION_STATE_ROAMING").getInt(null);
        Method get = ServiceState.class.getMethod("getNetworkRegistrationInfo", int.class, int.class);
        Method rawState = network.getMethod("getNetworkRegistrationState");
        Method registeredPlmn = network.getMethod("getRegisteredPlmn");
        Registration selected = null;
        for (String domain : new String[]{"DOMAIN_PS", "DOMAIN_CS"}) {
            Object info = get.invoke(state, network.getField(domain).getInt(null), wwan);
            if (info == null) continue;
            int actual = (Integer) rawState.invoke(info);
            if (actual != home && actual != roaming) continue;
            Object plmn = registeredPlmn.invoke(info);
            if (!(plmn instanceof String) || !((String) plmn).matches("[0-9]{5,6}")) return null;
            Registration next = new Registration((String) plmn, actual == roaming);
            if (selected != null && !selected.equals(next)) return null;
            selected = next;
        }
        return selected;
    }
}
