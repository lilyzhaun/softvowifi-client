package com.vowifi.sw;

import java.lang.reflect.InvocationTargetException;

public final class ProvisioningImsIdentity {
    public static final String AVAILABLE = "available";
    public static final String EMPTY = "empty";
    public static final String UNSUPPORTED = "unsupported";
    public static final String DENIED = "denied";
    public static final String INVALID = "invalid";
    public static final String MODE_IDENTITY = "identity";
    public static final String[] TEMPLATE_KEYS = {
        "impi", "sipImpu", "imsHomeDomain", "imsIdentityReadStatus",
    };

    public enum Kind {
        VALUE,
        EMPTY,
        UNSUPPORTED,
        DENIED,
        INVALID
    }

    public static final class Field {
        public final Kind kind;
        public final String value;

        private Field(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        public static Field empty() { return new Field(Kind.EMPTY, ""); }
        public static Field unsupported() { return new Field(Kind.UNSUPPORTED, ""); }
        public static Field denied() { return new Field(Kind.DENIED, ""); }
        public static Field invalid() { return new Field(Kind.INVALID, ""); }

        public static Field value(String raw) {
            if (raw == null) {
                return empty();
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return empty();
            }
            if (hasForbidden(trimmed)) {
                return invalid();
            }
            return new Field(Kind.VALUE, trimmed);
        }

        public static Field fromInvoke(Throwable error) {
            for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
                if (cursor instanceof NoSuchMethodException) {
                    return unsupported();
                }
                if (cursor instanceof SecurityException) {
                    return denied();
                }
                if (cursor instanceof InvocationTargetException) {
                    Throwable target = ((InvocationTargetException) cursor).getTargetException();
                    if (target instanceof SecurityException) {
                        return denied();
                    }
                    if (target instanceof NoSuchMethodException) {
                        return unsupported();
                    }
                }
            }
            return invalid();
        }

        public static Field fromImpuList(String[] raw) {
            if (raw == null || raw.length == 0) {
                return empty();
            }
            boolean present = false;
            for (int i = 0; i < raw.length; i++) {
                Field item = value(raw[i]);
                if (item.kind == Kind.EMPTY) {
                    continue;
                }
                present = true;
                if (item.kind == Kind.VALUE && isSipImpu(item.value)) {
                    return item;
                }
            }
            return present ? invalid() : empty();
        }
    }

    public static final class Bundle {
        public final String impi;
        public final String sipImpu;
        public final String imsHomeDomain;
        public final String status;

        Bundle(String impi, String sipImpu, String imsHomeDomain, String status) {
            this.impi = impi;
            this.sipImpu = sipImpu;
            this.imsHomeDomain = imsHomeDomain;
            this.status = status;
        }
    }

    private ProvisioningImsIdentity() {}

    public static Bundle classify(Field impi, Field domain, Field impu) {
        if (impi.kind == Kind.DENIED || domain.kind == Kind.DENIED || impu.kind == Kind.DENIED) {
            return unavailable(DENIED);
        }
        if (impi.kind == Kind.UNSUPPORTED
                || domain.kind == Kind.UNSUPPORTED
                || impu.kind == Kind.UNSUPPORTED) {
            return unavailable(UNSUPPORTED);
        }
        String impiValue = validated(impi, true, false);
        String domainValue = validated(domain, false, true);
        String impuValue = validated(impu, false, false);
        if (impiValue == null || domainValue == null || impuValue == null) {
            return unavailable(INVALID);
        }
        int filled = 0;
        if (!impiValue.isEmpty()) {
            filled++;
        }
        if (!domainValue.isEmpty()) {
            filled++;
        }
        if (!impuValue.isEmpty()) {
            filled++;
        }
        if (filled == 0) {
            return unavailable(EMPTY);
        }
        if (filled != 3) {
            return unavailable(INVALID);
        }
        return new Bundle(impiValue, impuValue, domainValue, AVAILABLE);
    }

    public static boolean isIdentityMode(String[] args) {
        return args != null && args.length == 3 && MODE_IDENTITY.equals(args[2]);
    }

    public static Bundle readFromBinder(Class<?> iface, Object svc, int subId, String callingPackage) {
        return classify(
            invoke(iface, svc, "getImsPrivateUserIdentity",
                new Class<?>[] {int.class, String.class, String.class},
                new Object[] {subId, callingPackage, null}, false),
            invoke(iface, svc, "getIsimDomain", new Class<?>[] {int.class}, new Object[] {subId}, false),
            invoke(iface, svc, "getIsimImpu", new Class<?>[] {int.class}, new Object[] {subId}, true)
        );
    }

    private static Field invoke(Class<?> iface, Object svc, String method, Class<?>[] types, Object[] args, boolean impu) {
        try {
            Object value = iface.getMethod(method, types).invoke(svc, args);
            if (value == null) {
                return Field.empty();
            }
            if (impu && value instanceof String[]) {
                return Field.fromImpuList((String[]) value);
            }
            if (value instanceof String) {
                return Field.value((String) value);
            }
            return Field.invalid();
        } catch (Throwable error) {
            return Field.fromInvoke(error);
        }
    }

    static boolean isSipImpu(String raw) {
        if (raw == null || hasForbidden(raw) || raw.indexOf(' ') >= 0) {
            return false;
        }
        if (!raw.startsWith("sip:") || raw.startsWith("sips:")) {
            return false;
        }
        int at = raw.indexOf('@', 4);
        if (at <= 4 || raw.indexOf('@', at + 1) >= 0) {
            return false;
        }
        String host = hostPart(raw.substring(at + 1));
        return at > 4 && !host.isEmpty() && host.indexOf(' ') < 0;
    }

    static boolean isImpi(String raw) {
        if (raw == null || hasForbidden(raw) || raw.indexOf(' ') >= 0) {
            return false;
        }
        int at = raw.indexOf('@');
        if (at <= 0 || at != raw.lastIndexOf('@') || at == raw.length() - 1) {
            return false;
        }
        return isHomeHost(raw.substring(at + 1));
    }

    static boolean isHomeHost(String raw) {
        if (raw == null || raw.isEmpty() || hasForbidden(raw)) {
            return false;
        }
        if (raw.indexOf(' ') >= 0 || raw.indexOf('@') >= 0 || raw.indexOf('/') >= 0
                || raw.indexOf(';') >= 0 || raw.indexOf('?') >= 0) {
            return false;
        }
        if (raw.indexOf(':') >= 0 && !raw.startsWith("[")) {
            return false;
        }
        return true;
    }

    private static String validated(Field field, boolean impi, boolean home) {
        if (field.kind == Kind.EMPTY) {
            return "";
        }
        if (field.kind != Kind.VALUE) {
            return null;
        }
        if (impi && isImpi(field.value)) {
            return field.value;
        }
        if (home && isHomeHost(field.value)) {
            return field.value;
        }
        if (!impi && !home && isSipImpu(field.value)) {
            return field.value;
        }
        return null;
    }

    private static Bundle unavailable(String status) {
        return new Bundle("", "", "", status);
    }

    private static String hostPart(String rest) {
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == ';' || ch == '?' || ch == '>') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    private static boolean hasForbidden(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
