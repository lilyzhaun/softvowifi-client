package com.vowifi.sw.network;

import android.os.IBinder;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONException;

/** Only root app_process uses hidden telephony APIs; the management APK gains no new permission. */
final class CellularPhone implements CellularController.Phone {
    private final Class<?> phoneType;
    private final Object phone;

    CellularPhone() throws Exception {
        phoneType = Class.forName("com.android.internal.telephony.ITelephony");
        phone = service("phone", phoneType);
    }

    private static Object service(String name, Class<?> type) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
            .invoke(null, name);
        if (binder == null) throw new CellularController.Failure("PHONE_NOT_READY");
        return Class.forName(type.getName() + "$Stub").getMethod("asInterface", IBinder.class)
            .invoke(null, binder);
    }

    private Object call(String method, Class<?>[] types, Object... values) throws Exception {
        return phoneType.getMethod(method, types).invoke(phone, values);
    }

    private static Object info(Object value, String method) throws Exception {
        return value.getClass().getMethod(method).invoke(value);
    }

    public CellularController.Card read(String key) throws Exception {
        Class<?> subType = Class.forName("com.android.internal.telephony.ISub");
        Object subscriptions = subType.getMethod("getActiveSubscriptionInfoList",
            String.class, String.class, boolean.class).invoke(service("isub", subType), "root", null, false);
        if (!(subscriptions instanceof List)) throw new CellularController.Failure("SIM_NOT_READY");
        Object target = null;
        boolean allCallsIdle = true;
        for (Object subscription : (List<?>) subscriptions) {
            int subId = ((Number) info(subscription, "getSubscriptionId")).intValue();
            allCallsIdle &= ((Number) call("getCallStateForSubscription",
                new Class<?>[]{int.class, String.class, String.class}, subId, "root", null)).intValue() == 0;
            String id = ((String) info(subscription, "getIccId")).replaceFirst("[Ff]+$", "");
            if (!id.matches("[0-9]{10,32}")) throw new CellularController.Failure("SIM_NOT_READY");
            allCallsIdle &= softwareCallIdle(id);
            if (key.equals(id)) {
                if (target != null) throw new CellularController.Failure("SIM_AMBIGUOUS");
                target = subscription;
            }
        }
        if (target == null) throw new CellularController.Failure("SIM_NOT_ACTIVE");
        int slot = ((Number) info(target, "getSimSlotIndex")).intValue();
        int subId = ((Number) info(target, "getSubscriptionId")).intValue();
        if (slot < 0 || slot > 7 || ((Number) call("getSimStateForSlotIndex",
            new Class<?>[]{int.class}, slot)).intValue() != 10) {
            throw new CellularController.Failure("SIM_NOT_READY");
        }
        boolean modem = (Boolean) call("isModemEnabledForSlot",
            new Class<?>[]{int.class, String.class, String.class}, slot, "root", null);
        Object state = call("getServiceStateForSlot", new Class<?>[]{int.class, boolean.class,
            boolean.class, String.class, String.class}, slot, true, true, "root", null);
        if (state == null) throw new CellularController.Failure("STATE_UNAVAILABLE");
        Method registration = state.getClass().getMethod("getNetworkRegistrationInfo", int.class, int.class);
        Object cs = registration.invoke(state, 1, 1);
        Object ps = registration.invoke(state, 2, 1);
        if (cs == null || ps == null) throw new CellularController.Failure("STATE_UNAVAILABLE");
        return new CellularController.Card(slot, subId, modem,
            (Boolean) info(cs, "isRegistered"), (Boolean) info(ps, "isRegistered"), allCallsIdle,
            (Boolean) call("isVoWiFiSettingEnabled", new Class<?>[]{int.class}, subId),
            (Boolean) call("isImsRegistered", new Class<?>[]{int.class}, subId));
    }

    public boolean setEnabled(int slot, boolean enabled) throws Exception {
        if (!enabled) throw new CellularController.Failure("FEATURE_REMOVED");
        return (Boolean) call("enableModemForSlot", new Class<?>[]{int.class, boolean.class}, slot, enabled);
    }

    private static boolean softwareCallIdle(String key) throws Exception {
        String path = "/data/local/tmp/vowifi/" + key + ".sock";
        if (!Files.exists(Paths.get(path))) return true;
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("{\"id\":701,\"method\":\"getStatus\",\"params\":{}}\n"
                .getBytes(StandardCharsets.US_ASCII));
            for (int frame = 0; frame < 4; frame++) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                boolean complete = false;
                for (int n = 0; n < 4096; n++) {
                    int value = socket.getInputStream().read();
                    if (value < 0) break;
                    if (value == '\n') { complete = true; break; }
                    bytes.write(value);
                }
                if (!complete) throw new CellularController.Failure("STATE_UNAVAILABLE");
                JSONObject message = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (message.optInt("id", -1) != 701) continue;
                JSONObject result = message.optJSONObject("result");
                if (result == null || !result.has("call")) throw new CellularController.Failure("STATE_UNAVAILABLE");
                return "IDLE".equals(result.getString("call"));
            }
        } catch (IOException | JSONException error) {
            throw new CellularController.Failure("SOFTWARE_STATE_UNAVAILABLE");
        }
        throw new CellularController.Failure("STATE_UNAVAILABLE");
    }
}
