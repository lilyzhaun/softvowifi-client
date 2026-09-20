package com.vowifi.sw;

import android.os.IBinder;
import com.vowifi.sw.manager.data.IccidNormalizer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;

public final class ProvisioningIdentityMain {
    private static final int MAX_STDIN_BYTES = 65537;
    private static final String CALLING_PACKAGE = "root";
    private static final String SERVICE = "iphonesubinfo";

    private ProvisioningIdentityMain() {}

    public static void main(String[] args) {
        byte[] stdin = null;
        try {
            if (args == null || (args.length != 2 && !ProvisioningImsIdentity.isIdentityMode(args))) {
                throw new IllegalArgumentException();
            }
            int subId = parseNonNegativeInt(args[0]);
            int slot = parseSlot(args[1]);
            boolean identityOnly = ProvisioningImsIdentity.isIdentityMode(args);
            stdin = readStdinBounded();
            JSONObject template = new JSONObject(new String(stdin, StandardCharsets.UTF_8));
            if (template.length() == 0) {
                throw new IllegalArgumentException();
            }
            String templateIccid = IccidNormalizer.INSTANCE.normalize(template.getString("iccid"));
            if (template.getInt("slot") != slot) {
                throw new IllegalArgumentException();
            }
            clearIdentityFields(template);
            Object svc = phoneSubInfo();
            Class<?> iface = Class.forName("com.android.internal.telephony.IPhoneSubInfo");
            String imsi = invokeIdentity(iface, svc, "getSubscriberIdForSubscriber", subId);
            if (templateIccid == null || !isAsciiDigits(imsi, 15, 15)) {
                throw new IllegalArgumentException();
            }
            ProvisioningImsIdentity.Bundle identity = ProvisioningImsIdentity.readFromBinder(
                iface, svc, subId, CALLING_PACKAGE);
            if (identityOnly) {
                template = identityOnlyJson(templateIccid, slot, imsi, identity);
            } else {
                String imei = invokeIdentity(iface, svc, "getDeviceIdForPhone", slot);
                if (!isAsciiDigits(imei, 15, 15)) {
                    throw new IllegalArgumentException();
                }
                template.put("iccid", templateIccid);
                template.put("imsi", imsi);
                template.put("imei", imei);
                template.put("imsIdentityMode", "auto");
                template.put("impi", identity.impi);
                template.put("sipImpu", identity.sipImpu);
                template.put("imsHomeDomain", identity.imsHomeDomain);
                template.put("imsIdentityReadStatus", identity.status);
                stripTransientSubIdKeys(template);
            }
            String out = template.toString();
            if (out.indexOf('\n') >= 0 || out.indexOf('\r') >= 0) {
                throw new IllegalArgumentException();
            }
            System.out.println(out);
        } catch (Throwable ignored) {
            wipe(stdin);
            System.exit(18);
            return;
        }
        wipe(stdin);
    }

    private static JSONObject identityOnlyJson(
        String iccid,
        int slot,
        String imsi,
        ProvisioningImsIdentity.Bundle identity
    ) throws Exception {
        JSONObject out = new JSONObject();
        out.put("iccid", iccid);
        out.put("slot", slot);
        out.put("imsi", imsi);
        out.put("impi", identity.impi);
        out.put("sipImpu", identity.sipImpu);
        out.put("imsHomeDomain", identity.imsHomeDomain);
        out.put("imsIdentityReadStatus", identity.status);
        return out;
    }

    private static void clearIdentityFields(JSONObject template) {
        for (int i = 0; i < ProvisioningImsIdentity.TEMPLATE_KEYS.length; i++) {
            template.remove(ProvisioningImsIdentity.TEMPLATE_KEYS[i]);
        }
    }

    private static Object phoneSubInfo() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        Object binder = getService.invoke(null, SERVICE);
        if (!(binder instanceof IBinder)) {
            throw new IllegalStateException();
        }
        Class<?> stub = Class.forName("com.android.internal.telephony.IPhoneSubInfo$Stub");
        Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (svc == null) {
            throw new IllegalStateException();
        }
        return svc;
    }

    private static String invokeIdentity(Class<?> iface, Object svc, String method, int numeric) throws Exception {
        Object value = iface.getMethod(method, int.class, String.class, String.class)
            .invoke(svc, numeric, CALLING_PACKAGE, null);
        if (!(value instanceof String)) {
            throw new IllegalStateException();
        }
        return (String) value;
    }

    private static byte[] readStdinBounded() throws Exception {
        InputStream in = System.in;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (n == 0) {
                continue;
            }
            total += n;
            if (total > MAX_STDIN_BYTES) {
                wipe(chunk);
                throw new IllegalArgumentException();
            }
            buf.write(chunk, 0, n);
        }
        wipe(chunk);
        if (total == 0) {
            throw new IllegalArgumentException();
        }
        return buf.toByteArray();
    }

    private static int parseNonNegativeInt(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException();
        }
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new IllegalArgumentException();
            }
        }
        long value = Long.parseLong(raw);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException();
        }
        return (int) value;
    }

    private static int parseSlot(String raw) {
        if ("0".equals(raw)) {
            return 0;
        }
        if ("1".equals(raw)) {
            return 1;
        }
        throw new IllegalArgumentException();
    }

    private static boolean isAsciiDigits(String value, int min, int max) {
        if (value == null) {
            return false;
        }
        int n = value.length();
        if (n < min || n > max) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    private static void stripTransientSubIdKeys(JSONObject json) {
        Iterator<String> keys = json.keys();
        List<String> remove = new ArrayList<String>();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isTransientSubIdKey(key)) {
                remove.add(key);
            }
        }
        for (int i = 0; i < remove.size(); i++) {
            json.remove(remove.get(i));
        }
    }

    private static boolean isTransientSubIdKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("_", "");
        return normalized.equalsIgnoreCase("subscriptionId") || normalized.equalsIgnoreCase("subId");
    }

    private static void wipe(byte[] data) {
        if (data == null) {
            return;
        }
        for (int i = 0; i < data.length; i++) {
            data[i] = 0;
        }
    }
}
