package com.vowifi.sw.root;

import android.os.IBinder;
import android.telephony.SubscriptionInfo;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Root-only telephony adapter. Subscriber secrets stay in the provisioning process. */
final class RootTelephony {
    record Snapshot(JSONArray bindings, JSONObject summary) {}

    static List<SubscriptionInfo> activeSubscriptions() throws Exception {
        Class<?> type = Class.forName("com.android.internal.telephony.ISub");
        Object active = type.getMethod("getActiveSubscriptionInfoList", String.class, String.class, boolean.class)
            .invoke(service("isub", type), "root", null, true);
        if (!(active instanceof List<?>)) throw new IllegalStateException("subscriptions_unavailable");
        java.util.ArrayList<SubscriptionInfo> result = new java.util.ArrayList<>();
        for (Object value : (List<?>) active) if (value instanceof SubscriptionInfo) result.add((SubscriptionInfo) value);
        return result;
    }

    static String subscriptionExtras(int subId) throws Exception {
        if (activeSubscriptions().stream().noneMatch(info -> info.getSubscriptionId() == subId))
            throw new IllegalArgumentException("inactive_subscription");
        JSONObject extra = new JSONObject();
        Class<?> phone = Class.forName("com.android.internal.telephony.IPhoneSubInfo");
        try {
            Object number = phone.getMethod("getLine1NumberForSubscriber", int.class, String.class, String.class)
                .invoke(service("iphonesubinfo", phone), subId, "root", null);
            if (number instanceof String) extra.put("line1", number);
        } catch (ReflectiveOperationException | SecurityException ignored) { /* Optional EF_MSISDN. */ }
        Class<?> sms = Class.forName("com.android.internal.telephony.ISms");
        try {
            Object smsc = sms.getMethod("getSmscAddressFromIccEfForSubscriber", int.class, String.class)
                .invoke(service("isms", sms), subId, "root");
            if (smsc instanceof String) extra.put("smsc", smsc);
        } catch (ReflectiveOperationException | SecurityException ignored) { /* Optional EF_SMSP. */ }
        return extra.toString();
    }

    static String nativeStatus() throws Exception {
        Class<?> phone = Class.forName("com.android.internal.telephony.ITelephony");
        Object binder = service("phone", phone);
        JSONArray cards = new JSONArray();
        for (SubscriptionInfo info : activeSubscriptions()) {
            cards.put(new JSONObject().put("slot", info.getSimSlotIndex()).put("subId", info.getSubscriptionId())
                .put("enabled", booleanCall(phone, binder, "isVoWiFiSettingEnabled", info.getSubscriptionId()))
                .put("registered", booleanCall(phone, binder, "isImsRegistered", info.getSubscriptionId())));
        }
        return cards.toString();
    }

    // Same read-only native-call gate as the exported CellularPhone adapter.
    static boolean nativeCallsIdle() throws Exception {
        Class<?> phone = Class.forName("com.android.internal.telephony.ITelephony");
        Object binder = service("phone", phone);
        Method state = phone.getMethod("getCallStateForSubscription", int.class, String.class, String.class);
        List<SubscriptionInfo> active = activeSubscriptions();
        if (active.isEmpty()) return false;
        for (SubscriptionInfo info : active) {
            Object value = state.invoke(binder, info.getSubscriptionId(), "root", null);
            if (!(value instanceof Integer) || ((Integer) value) != 0) return false;
        }
        return true;
    }

    static Snapshot read() throws Exception {
        Class<?> subscriptionType = Class.forName("com.android.internal.telephony.ISub");
        Object subscriptionService = service("isub", subscriptionType);
        Object active = subscriptionType.getMethod("getActiveSubscriptionInfoList",
            String.class, String.class, boolean.class).invoke(subscriptionService, "root", null, true);
        if (!(active instanceof List<?>)) throw new IllegalStateException("subscription_unavailable");
        Class<?> identityType = Class.forName("com.android.internal.telephony.IPhoneSubInfo");
        Object identityService = service("iphonesubinfo", identityType);
        Class<?> phoneType = Class.forName("com.android.internal.telephony.ITelephony");
        Object phoneService = service("phone", phoneType);
        JSONArray bindings = new JSONArray();
        JSONArray summaries = new JSONArray();
        boolean[] seen = new boolean[2];
        for (Object item : (List<?>) active) {
            if (!(item instanceof SubscriptionInfo)) continue;
            SubscriptionInfo info = (SubscriptionInfo) item;
            int slot = info.getSimSlotIndex(), subId = info.getSubscriptionId();
            if (slot < 0 || slot > 1 || subId < 0 || seen[slot]) continue;
            seen[slot] = true;
            String iccid = info.getIccId();
            if (iccid != null) iccid = iccid.replaceFirst("[Ff]+$", "");
            String imsi = null;
            try {
                Object value = identityType.getMethod("getSubscriberIdForSubscriber", int.class,
                    String.class, String.class).invoke(identityService, subId, "root", null);
                if (value instanceof String) imsi = (String) value;
            } catch (ReflectiveOperationException | SecurityException ignored) { }
            boolean iccidReady = iccid != null && iccid.matches("[0-9]{15,22}");
            boolean imsiReady = imsi != null && imsi.matches("[0-9]{15}");
            JSONObject summary = new JSONObject().put("slot", slot)
                .put("iccid_readable", iccidReady).put("imsi_readable", imsiReady);
            summary.put("native_wfc", booleanCall(phoneType, phoneService, "isVoWiFiSettingEnabled", subId));
            summary.put("native_ims", booleanCall(phoneType, phoneService, "isImsRegistered", subId));
            summaries.put(summary);
            if (iccidReady && imsiReady) {
                bindings.put(new JSONObject().put("slot", slot).put("iccid", iccid).put("imsi", imsi));
            }
        }
        return new Snapshot(bindings, new JSONObject().put("status", "readable").put("sim", summaries));
    }

    private static Object booleanCall(Class<?> type, Object service, String method, int subId) {
        try {
            Object value = type.getMethod(method, int.class).invoke(service, subId);
            return value instanceof Boolean ? value : "unavailable";
        } catch (ReflectiveOperationException | SecurityException ignored) { return "unavailable"; }
    }

    private static Object service(String name, Class<?> type) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
            .invoke(null, name);
        if (!(binder instanceof IBinder)) throw new IllegalStateException("service_unavailable");
        Method asInterface = Class.forName(type.getName() + "$Stub").getMethod("asInterface", IBinder.class);
        Object service = asInterface.invoke(null, binder);
        if (service == null) throw new IllegalStateException("service_unavailable");
        return service;
    }
}
