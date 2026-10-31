package com.vowifi.sw;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.IBinder;
import java.lang.reflect.Method;

/** Root-only caller of the platform's user channel settings, not a service lifecycle change. */
// Launched only by app_process as UID 0 (checked in main), not in the target-SDK App VM.
@android.annotation.SuppressLint("SoonBlockedPrivateApi")
public final class ObserverNotificationChannelMain {
    private static final String PACKAGE = "com.vowifi.sw";
    private static final String CHANNEL = "ims_observer";

    private ObserverNotificationChannelMain() {}

    public static void main(String[] args) {
        boolean ready = false;
        try {
            if (android.os.Process.myUid() == 0 && args != null
                    && (args.length == 1 || (args.length == 2 && "repair-default-v2".equals(args[1])))
                    && args[0] != null && args[0].matches("[0-9]{1,10}")) {
                int uid = Integer.parseInt(args[0]);
                if (uid >= 10000) {
                    Object binder = Class.forName("android.os.ServiceManager")
                            .getMethod("getService", String.class).invoke(null, "notification");
                    Object manager = Class.forName("android.app.INotificationManager$Stub")
                            .getMethod("asInterface", IBinder.class).invoke(null, binder);
                    Class<?> managerType = Class.forName("android.app.INotificationManager");
                    ready = args.length == 2 ? applyRepair(manager, managerType, uid)
                            : apply(manager, managerType, uid);
                }
            }
        } catch (ReflectiveOperationException | NumberFormatException | SecurityException unavailable) {
            // Neither platform errors nor private arguments may reach stdout/stderr.
            ready = false;
        }
        System.out.println(ready ? "OBSERVER_CHANNEL_DEFAULT_READY" : "OBSERVER_CHANNEL_DEFAULT_UNAVAILABLE");
        if (!ready) System.exit(1);
    }

    static boolean apply(Object manager, Class<?> managerType, int uid)
            throws ReflectiveOperationException {
        if (manager == null || uid < 10000) return false;
        Method get = managerType.getMethod("getNotificationChannelForPackage",
                String.class, int.class, String.class, String.class, boolean.class);
        NotificationChannel channel = (NotificationChannel) get.invoke(manager, PACKAGE, uid, CHANNEL, null, false);
        if (channel == null || !CHANNEL.equals(channel.getId())) return false;
        if (channel.hasUserSetImportance() && channel.getImportance() != NotificationManager.IMPORTANCE_NONE) return true;
        Method shown = shownMethod("isUserVisibleTaskShown", "isFgServiceShown");
        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE
                && channel.hasUserSetImportance() && Boolean.TRUE.equals(shown.invoke(channel))) return true;
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        int importanceLock = NotificationChannel.class.getDeclaredField("USER_LOCKED_IMPORTANCE").getInt(null);
        NotificationChannel.class.getMethod("lockFields", int.class).invoke(channel, importanceLock);
        shownMethod("setUserVisibleTaskShown", "setFgServiceShown", boolean.class).invoke(channel, true);
        managerType.getMethod("updateNotificationChannelForPackage", String.class, int.class, NotificationChannel.class)
                .invoke(manager, PACKAGE, uid, channel);
        NotificationChannel verified = (NotificationChannel) get.invoke(manager, PACKAGE, uid, CHANNEL, null, false);
        return verified != null && CHANNEL.equals(verified.getId())
                && verified.getImportance() == NotificationManager.IMPORTANCE_NONE
                && verified.hasUserSetImportance() && Boolean.TRUE.equals(shown.invoke(verified));
    }

    /** Explicit hide request; the application owns the one-time migration marker. */
    static boolean applyRepair(Object manager, Class<?> managerType, int uid)
            throws ReflectiveOperationException {
        if (manager == null || uid < 10000) return false;
        Method get = managerType.getMethod("getNotificationChannelForPackage",
                String.class, int.class, String.class, String.class, boolean.class);
        Method update = managerType.getMethod("updateNotificationChannelForPackage",
                String.class, int.class, NotificationChannel.class);
        NotificationChannel channel = (NotificationChannel) get.invoke(manager, PACKAGE, uid, CHANNEL, null, false);
        if (channel == null || !CHANNEL.equals(channel.getId())) return false;
        if (!channel.isBlockable()) {
            int importance = channel.getImportance();
            channel.setBlockable(true);
            update.invoke(manager, PACKAGE, uid, channel);
            channel = (NotificationChannel) get.invoke(manager, PACKAGE, uid, CHANNEL, null, false);
            if (channel == null || !CHANNEL.equals(channel.getId()) || !channel.isBlockable()
                    || channel.getImportance() != importance) return false;
        }
        Method shown = shownMethod("isUserVisibleTaskShown", "isFgServiceShown");
        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE
                && channel.hasUserSetImportance() && Boolean.TRUE.equals(shown.invoke(channel))) return true;
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        int importanceLock = NotificationChannel.class.getDeclaredField("USER_LOCKED_IMPORTANCE").getInt(null);
        NotificationChannel.class.getMethod("lockFields", int.class).invoke(channel, importanceLock);
        shownMethod("setUserVisibleTaskShown", "setFgServiceShown", boolean.class).invoke(channel, true);
        update.invoke(manager, PACKAGE, uid, channel);
        NotificationChannel verified = (NotificationChannel) get.invoke(manager, PACKAGE, uid, CHANNEL, null, false);
        return verified != null && CHANNEL.equals(verified.getId()) && verified.isBlockable()
                && verified.getImportance() == NotificationManager.IMPORTANCE_NONE
                && verified.hasUserSetImportance() && Boolean.TRUE.equals(shown.invoke(verified));
    }

    private static Method shownMethod(String current, String legacy, Class<?>... parameters)
            throws NoSuchMethodException {
        try { return NotificationChannel.class.getMethod(current, parameters); }
        catch (NoSuchMethodException missing) { return NotificationChannel.class.getMethod(legacy, parameters); }
    }
}
