package com.vowifi.sw.root;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.telephony.SubscriptionInfo;
import com.topjohnwu.superuser.ipc.RootService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Process-wide connection shared by the exported manager and its foreground service. */
public final class RootBridge {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService WORK = Executors.newSingleThreadScheduledExecutor();
    private static final CopyOnWriteArrayList<Runnable> WATCHERS = new CopyOnWriteArrayList<>();
    private static volatile IRootBackend backend;
    private static volatile String environment;
    private static boolean binding;
    private static String subscriptionKey;
    private static volatile java.util.function.Consumer<String> nativeEvents;
    private static final INativeRegistrationEvents.Stub NATIVE_EVENTS = new INativeRegistrationEvents.Stub() {
        @Override public void onEvent(String event) {
            if (android.os.Binder.getCallingUid() != 0) throw new SecurityException("caller_denied");
            java.util.function.Consumer<String> sink = nativeEvents;
            if (sink != null) sink.accept(event);
        }
    };

    private RootBridge() {}

    public static void bind(Context context, Runnable ready) {
        synchronized (LOCK) {
            if (environment != null) { MAIN.post(ready); return; }
            if (binding) return;
            binding = true;
        }
        Context app = context.getApplicationContext();
        RootService.bind(new Intent(app, RootBackendService.class), new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                backend = IRootBackend.Stub.asInterface(service);
                WORK.execute(() -> {
                    try {
                        String prepared = backend.prepareRuntime();
                        synchronized (LOCK) { environment = prepared; LOCK.notifyAll(); }
                        MAIN.post(ready);
                        notifyWatchers();
                    } catch (RemoteException | RuntimeException error) {
                        disconnected();
                    }
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) { disconnected(); }
        });
    }

    private static void disconnected() {
        synchronized (LOCK) { environment = null; backend = null; binding = false; LOCK.notifyAll(); }
        notifyWatchers();
    }

    static {
        com.topjohnwu.superuser.Shell.enableVerboseLogging = false;
        com.topjohnwu.superuser.Shell.setDefaultBuilder(com.topjohnwu.superuser.Shell.Builder.create().setTimeout(15));
        WORK.scheduleWithFixedDelay(() -> {
            if (backend == null || WATCHERS.isEmpty()) return;
            try {
                StringBuilder key = new StringBuilder();
                for (SubscriptionInfo info : backend.activeSubscriptions()) {
                    key.append(info.getSubscriptionId()).append(':').append(info.getSimSlotIndex())
                        .append(':').append(info.getIccId()).append(';');
                }
                String next = key.toString();
                if (!next.equals(subscriptionKey)) {
                    subscriptionKey = next;
                    notifyWatchers();
                    if (nativeEvents != null) backend.observeNativeRegistration(NATIVE_EVENTS);
                }
            } catch (RemoteException | RuntimeException error) { notifyWatchers(); }
        }, 5, 5, TimeUnit.SECONDS);
    }

    public static void watchSubscriptions(Runnable watcher) { WATCHERS.add(watcher); }
    public static void unwatchSubscriptions(Runnable watcher) { WATCHERS.remove(watcher); }
    private static void notifyWatchers() { for (Runnable watcher : WATCHERS) MAIN.post(watcher); }

    private static IRootBackend requireBackend() {
        IRootBackend service = backend;
        if (service == null || environment == null) throw new IllegalStateException("Root 后端尚未连接");
        return service;
    }

    public static List<SubscriptionInfo> activeSubscriptions() {
        try { return requireBackend().activeSubscriptions(); }
        catch (RemoteException error) { throw new IllegalStateException("Root 订阅读取失败", error); }
    }

    public static JSONObject subscriptionExtras(int subId) {
        try { return new JSONObject(requireBackend().subscriptionExtras(subId)); }
        catch (RemoteException | org.json.JSONException error) { throw new IllegalStateException("Root 身份读取失败", error); }
    }

    public static org.json.JSONArray nativeStatus() {
        try { return new org.json.JSONArray(requireBackend().nativeStatus()); }
        catch (RemoteException | org.json.JSONException error) { throw new IllegalStateException("系统 IMS 状态读取失败", error); }
    }

    /** Busy and unavailable both deny audio admission; no native call is modified. */
    public static boolean nativeCallsIdle() {
        try { return requireBackend().nativeCallsIdle(); }
        catch (RemoteException | IllegalStateException error) { return false; }
    }

    public static void observeNativeRegistration(java.util.function.Consumer<String> events) {
        nativeEvents = events;
        try { requireBackend().observeNativeRegistration(events == null ? null : NATIVE_EVENTS); }
        catch (RemoteException error) { throw new IllegalStateException("系统 IMS 观察不可用", error); }
    }

    /** Keeps the existing manager command runners and their cancellation/output limits. */
    public static Process startCommand(String command) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        synchronized (LOCK) {
            while (environment == null && binding) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(LOCK, remaining); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Root 连接已取消", error); }
            }
        }
        String prepared = environment;
        if (prepared == null || backend == null) throw new IOException("Root 后端尚未连接，请授予 Root 权限");
        return new ProcessBuilder("su", "-c", prepared + "\n" + command).redirectErrorStream(true).start();
    }

    public static LocalSocket openSocket(String path) throws IOException {
        String prefix = "/data/local/tmp/softvowifi/";
        if (!path.startsWith(prefix)) throw new IOException("invalid_engine_path");
        String file = path.substring(prefix.length());
        boolean audio = file.endsWith(".audio.sock");
        String suffix = audio ? ".audio.sock" : ".sock";
        if (!file.endsWith(suffix)) throw new IOException("invalid_engine_path");
        String iccid = file.substring(0, file.length() - suffix.length());
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        LocalSocket socket = null;
        try {
            socket = new DescriptorSocket(pair[0]);
            socket.setSoTimeout(0);
            requireBackend().attachEngineStream(iccid, audio, pair[1]);
            return socket;
        } catch (RemoteException | RuntimeException | IOException error) {
            if (socket != null) socket.close(); else pair[0].close();
            throw new IOException("Root 引擎通道不可用", error);
        } finally { pair[1].close(); }
    }
}
