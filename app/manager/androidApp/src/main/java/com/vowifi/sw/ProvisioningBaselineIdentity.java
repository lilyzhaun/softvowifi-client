package com.vowifi.sw;

import android.os.IBinder;
import android.telephony.SubscriptionInfo;
import com.vowifi.sw.manager.data.IccidNormalizer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;

// Read-only active-subscription binding for a fresh baseline. The final
// EF_ICCID/EF_IMSI hardware authority remains the existing strict QMI gate.
public final class ProvisioningBaselineIdentity {
    private ProvisioningBaselineIdentity() {}

    // The module constructs this digit-only stdin target, never a config file.
    // Failures are a fixed exit code; no active ICCID, list or exception is logged.
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            byte[] raw = new byte[24];
            int length = 0;
            for (int value; (value = System.in.read()) != -1;) {
                if (length == raw.length) throw new IllegalArgumentException();
                raw[length++] = (byte) value;
            }
            if (length > 0 && raw[length - 1] == '\n') length--;
            String target = new String(raw, 0, length, StandardCharsets.US_ASCII);
            java.util.Arrays.fill(raw, (byte) 0);
            Object service = subscriptionService();
            Method get = Class.forName("com.android.internal.telephony.ISub").getMethod(
                "getActiveSubscriptionInfoList", String.class, String.class, boolean.class);
            Object active = get.invoke(service, "root", null, true);
            if (!(active instanceof List<?>)) throw new IllegalArgumentException();
            requireInactive((List<?>) active, target);
        } catch (Exception ignored) {
            System.exit(18);
        }
    }

    static void requireInactive(List<?> active, String target) {
        String normalized = IccidNormalizer.INSTANCE.normalize(target);
        if (normalized == null || !normalized.equals(target) || active == null) throw new IllegalArgumentException();
        for (Object value : active) {
            if (!(value instanceof SubscriptionInfo)) throw new IllegalArgumentException();
            SubscriptionInfo info = (SubscriptionInfo) value;
            String current = IccidNormalizer.INSTANCE.normalize(info.getIccId());
            if (current == null || current.equals(normalized) || info.getSubscriptionId() < 0 || info.getSimSlotIndex() < 0) {
                throw new IllegalArgumentException();
            }
        }
    }

    private static Object subscriptionService() throws Exception {
        Class<?> services = Class.forName("android.os.ServiceManager");
        Object binder = services.getMethod("getService", String.class).invoke(null, "isub");
        if (!(binder instanceof IBinder)) throw new IllegalArgumentException();
        Class<?> stub = Class.forName("com.android.internal.telephony.ISub$Stub");
        Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (service == null) throw new IllegalArgumentException();
        return service;
    }

    static JSONObject readTemplate(JSONObject target, int subId, int slot) throws Exception {
        Object service = subscriptionService();
        Class<?> contract = Class.forName("com.android.internal.telephony.ISub");
        Method get = contract.getMethod("getActiveSubscriptionInfo", int.class, String.class, String.class);
        Object value = get.invoke(service, subId, "root", null);
        if (!(value instanceof SubscriptionInfo)) throw new IllegalArgumentException();
        return template(target, (SubscriptionInfo) value, subId, slot);
    }

    static JSONObject template(JSONObject target, SubscriptionInfo active, int subId, int slot) throws Exception {
        if (active == null || active.getSubscriptionId() != subId || active.getSimSlotIndex() != slot ||
            !(target.get("slot") instanceof Integer) || target.getInt("slot") != slot ||
            !(target.get("iccid") instanceof String)) throw new IllegalArgumentException();
        String expected = IccidNormalizer.INSTANCE.normalize(target.getString("iccid"));
        String current = IccidNormalizer.INSTANCE.normalize(active.getIccId());
        String mcc = active.getMccString();
        String mnc = active.getMncString();
        if (current == null || !current.equals(expected) || !digits(mcc, 3, 3) || !digits(mnc, 2, 3)) {
            throw new IllegalArgumentException();
        }
        JSONObject fresh = new JSONObject();
        fresh.put("iccid", current);
        fresh.put("slot", slot);
        fresh.put("mcc", mcc);
        fresh.put("mnc", mnc);
        fresh.put("mncLen", mnc.length());
        fresh.put("enabled", true);
        fresh.put("transport", "wifi");
        if (active.getCarrierId() > 0) fresh.put("carrierId", active.getCarrierId());
        return fresh;
    }

    static void requireSameBinding(JSONObject before, JSONObject after) throws Exception {
        for (String key : new String[] {"iccid", "slot", "mcc", "mnc", "mncLen", "carrierId"}) {
            Object old = before.opt(key);
            Object current = after.opt(key);
            if (old == null ? current != null : !old.equals(current)) throw new IllegalArgumentException();
        }
    }

    private static boolean digits(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return true;
    }
}
