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
    private static final java.util.List<Runnable> PENDING_READY = new java.util.ArrayList<>();
    private static volatile IRootBackend backend;
    private static volatile String environment;
    private static boolean binding;
    private static volatile String subscriptionKey;
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
            if (environment != null && backend != null) {
                if (ready != null) MAIN.post(ready);
                return;
            }
            if (ready != null) PENDING_READY.add(ready);
            if (binding) return;
            binding = true;
        }
        Context app = context.getApplicationContext();
        try {
            RootService.bind(new Intent(app, RootBackendService.class), new ServiceConnection() {
                private volatile IRootBackend attached;

                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    IRootBackend connected = IRootBackend.Stub.asInterface(service);
                    attached = connected;
                    synchronized (LOCK) { environment = null; backend = connected; }
                    WORK.execute(() -> {
                        try {
                            String prepared = connected.prepareRuntime();
                            java.util.List<Runnable> callbacks;
                            boolean reattachNative;
                            synchronized (LOCK) {
                                if (backend != connected) return;
                                environment = prepared;
                                binding = false;
                                callbacks = new java.util.ArrayList<>(PENDING_READY);
                                PENDING_READY.clear();
                                reattachNative = nativeEvents != null;
                                LOCK.notifyAll();
                            }
                            if (reattachNative) {
                                try {
                                    connected.observeNativeRegistration(NATIVE_EVENTS);
                                    if (nativeEvents == null) connected.observeNativeRegistration(null);
                                } catch (RemoteException | RuntimeException ignored) { }
                            }
                            for (Runnable callback : callbacks) MAIN.post(callback);
                            notifyWatchers();
                        } catch (RemoteException | RuntimeException error) {
                            disconnected(connected);
                        }
                    });
                }

                @Override public void onServiceDisconnected(ComponentName name) {
                    disconnected(attached);
                }
            });
        } catch (RuntimeException error) {
            synchronized (LOCK) {
                binding = false;
                PENDING_READY.clear();
            }
            throw error;
        }
    }

    private static void disconnected(IRootBackend expected) {
        boolean changed;
        synchronized (LOCK) {
            if (expected != null && backend != expected) return;
            changed = backend != null || environment != null || binding;
            environment = null;
            backend = null;
            binding = false;
            subscriptionKey = null;
            PENDING_READY.clear();
            LOCK.notifyAll();
        }
        if (changed) notifyWatchers();
    }

    static {
        com.topjohnwu.superuser.Shell.enableVerboseLogging = false;
        com.topjohnwu.superuser.Shell.setDefaultBuilder(com.topjohnwu.superuser.Shell.Builder.create().setTimeout(15));
        WORK.scheduleWithFixedDelay(() -> checkSubscriptions(backend), 5, 5, TimeUnit.SECONDS);
    }

    static void checkSubscriptions(IRootBackend service) {
        if (service == null || WATCHERS.isEmpty()) return;
        try {
            StringBuilder key = new StringBuilder();
            for (SubscriptionInfo info : service.activeSubscriptions()) {
                key.append(info.getSubscriptionId()).append(':').append(info.getSimSlotIndex())
                    .append(':').append(Math.max(-1, info.getPortIndex())).append(':').append(info.getIccId()).append(';');
            }
            String next = key.toString();
            if (!next.equals(subscriptionKey)) {
                subscriptionKey = next;
                notifyWatchers();
                if (nativeEvents != null) service.observeNativeRegistration(NATIVE_EVENTS);
            }
        } catch (RemoteException | RuntimeException error) { notifyWatchers(); }
    }

    public static void watchSubscriptions(Runnable watcher) { WATCHERS.add(watcher); }
    public static void unwatchSubscriptions(Runnable watcher) { WATCHERS.remove(watcher); }
    private static void notifyWatchers() { for (Runnable watcher : WATCHERS) MAIN.post(watcher); }

    private static IRootBackend requireBackend() {
        synchronized (LOCK) {
            if (backend == null || environment == null) throw new IllegalStateException("Root 后端尚未连接");
            return backend;
        }
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

    public static org.json.JSONObject registeredAccess() {
        try {
            String value = requireBackend().registeredAccess();
            return value == null ? null : new org.json.JSONObject(value);
        } catch (RemoteException | RuntimeException | org.json.JSONException error) { return null; }
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
        String prepared;
        synchronized (LOCK) {
            // Activity read sources can start before the Observer's first bind.
            // That ordering uses the same bounded wait as an in-flight bind.
            while (environment == null || backend == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(LOCK, remaining); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Root 连接已取消", error); }
            }
            prepared = backend == null ? null : environment;
        }
        if (prepared == null) throw new IOException("Root 后端尚未连接，请授予 Root 权限");
        return startPrepared(prepared, command);
    }

    /** A multi-step read must never borrow the next RootService after reconnecting. */
    public static CommandSession captureCommandSession() {
        synchronized (LOCK) {
            return backend == null || environment == null ? null : new CommandSession(backend, environment);
        }
    }

    public static final class CommandSession {
        private final IRootBackend owner;
        private final String prepared;
        private CommandSession(IRootBackend owner, String prepared) { this.owner = owner; this.prepared = prepared; }
        public boolean isCurrent() {
            synchronized (LOCK) { return backend == owner && environment == prepared; }
        }
        public Process start(String command) throws IOException {
            if (!isCurrent()) throw new IOException("Root 连接已变化");
            return startPrepared(prepared, command);
        }
    }

    private static Process startPrepared(String prepared, String command) throws IOException {
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
            try { if (socket != null) socket.close(); else pair[0].close(); } catch (IOException ignored) { }
            throw new IOException("Root 引擎通道不可用", error);
        } finally {
            try { pair[1].close(); } catch (IOException ignored) { }
        }
    }
}
