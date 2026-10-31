package com.vowifi.sw.root;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import com.topjohnwu.superuser.ipc.RootService;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RootBackendService extends RootService {
    private final Object probeLock = new Object();
    private volatile java.lang.Process child;
    private volatile ParcelFileDescriptor stream;
    private volatile String streamStatus = "not_started";
    private final java.util.Map<Long, RootStream> streams = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong streamIds = new java.util.concurrent.atomic.AtomicLong();
    private final RootRuntime runtime = new RootRuntime(this);
    private final RootNativeObservation nativeObservation = new RootNativeObservation(this);

    private void requireCaller() {
        int caller = Binder.getCallingUid();
        try {
            int owner = getPackageManager().getApplicationInfo(getPackageName(), 0).uid;
            if (caller != owner || Process.myUid() != 0) throw new SecurityException("caller_denied");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(getPackageManager()
                .getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo.getApkContentsSigners()[0].toByteArray());
            if (!getPackageManager().hasSigningCertificate(caller, digest, PackageManager.CERT_INPUT_SHA256)) {
                throw new SecurityException("signer_denied");
            }
            enforceCallingPermission("com.vowifi.sw.permission.ROOT_SERVICE", "caller_denied");
        } catch (SecurityException error) { throw error;
        } catch (Exception ignored) { throw new SecurityException("caller_unavailable"); }
    }

    private final IRootBackend.Stub binder = new IRootBackend.Stub() {
        @Override public String runReadOnlyChecks() {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (probeLock) { return inspect().toString(); }
            } catch (Exception ignored) { return "{\"schema\":1,\"error\":\"root_check_unavailable\"}";
            } finally { Binder.restoreCallingIdentity(token); }
        }

        @Override public void attachProbeStream(ParcelFileDescriptor descriptor) {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            ParcelFileDescriptor accepted = null;
            try {
                synchronized (probeLock) {
                    if (stream != null) throw new IllegalStateException("probe_busy");
                    streamStatus = "accept_app_descriptor";
                    if (descriptor == null || !OsConstants.S_ISSOCK(Os.fstat(descriptor.getFileDescriptor()).st_mode)) {
                        throw new IllegalArgumentException("not_stream");
                    }
                    accepted = ParcelFileDescriptor.dup(descriptor.getFileDescriptor());
                    streamStatus = "configure_pair";
                    Os.setsockoptTimeval(accepted.getFileDescriptor(), OsConstants.SOL_SOCKET,
                        OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(3000));
                    Os.setsockoptTimeval(accepted.getFileDescriptor(), OsConstants.SOL_SOCKET,
                        OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(3000));
                    stream = accepted;
                    streamStatus = "ready";
                    final ParcelFileDescriptor owned = accepted;
                    new Thread(() -> echo(owned), "root-stream-probe").start();
                }
            } catch (Exception error) {
                streamStatus = streamStatus + ":" + failureCode(error);
                if (accepted != null) try { accepted.close(); } catch (Exception closeIgnored) { }
                throw new IllegalStateException("stream_unavailable");
            } finally {
                if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { }
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override public String getProbeStreamStatus() {
            requireCaller();
            return streamStatus;
        }

        @Override public java.util.List<android.telephony.SubscriptionInfo> activeSubscriptions() {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            try { return RootTelephony.activeSubscriptions(); }
            catch (Exception error) { throw new IllegalStateException("subscriptions_unavailable"); }
            finally { Binder.restoreCallingIdentity(token); }
        }

        @Override public String subscriptionExtras(int subId) {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            try { return RootTelephony.subscriptionExtras(subId); }
            catch (Exception error) { throw new IllegalStateException("subscription_identity_unavailable"); }
            finally { Binder.restoreCallingIdentity(token); }
        }

        @Override public String prepareRuntime() {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            try { return runtime.prepare(); }
            catch (Exception error) { throw new IllegalStateException("root_runtime_unavailable"); }
            finally { Binder.restoreCallingIdentity(token); }
        }

        @Override public String nativeStatus() {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            try { return RootTelephony.nativeStatus(); }
            catch (Exception error) { throw new IllegalStateException("native_status_unavailable"); }
            finally { Binder.restoreCallingIdentity(token); }
        }

        @Override public boolean nativeCallsIdle() {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            try { return RootTelephony.nativeCallsIdle(); }
            catch (Exception error) { throw new IllegalStateException("native_call_state_unavailable"); }
            finally { Binder.restoreCallingIdentity(token); }
        }

        @Override public void observeNativeRegistration(INativeRegistrationEvents events) {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            try { nativeObservation.observe(events); }
            catch (Exception error) { throw new IllegalStateException("native_observer_unavailable"); }
            finally { Binder.restoreCallingIdentity(token); }
        }

        @Override public void attachEngineStream(String iccid, boolean audio, ParcelFileDescriptor descriptor) {
            requireCaller();
            long token = Binder.clearCallingIdentity();
            long id = streamIds.incrementAndGet();
            RootStream relay = null;
            try {
                synchronized (streams) {
                    if (streams.size() >= 16) throw new IllegalStateException("engine_stream_limit");
                    relay = new RootStream(iccid, audio, descriptor, () -> streams.remove(id));
                    streams.put(id, relay);
                }
                relay.start();
            } catch (Exception error) {
                if (relay != null) relay.close();
                throw new IllegalStateException("engine_stream_unavailable");
            } finally {
                if (descriptor != null) try { descriptor.close(); } catch (java.io.IOException ignored) { }
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    private JSONObject inspect() throws Exception {
        JSONObject report = new JSONObject().put("schema", 1).put("root_uid", Process.myUid());
        JSONArray bindings = new JSONArray();
        try {
            RootTelephony.Snapshot snapshot = RootTelephony.read();
            bindings = snapshot.bindings();
            report.put("framework", snapshot.summary());
        } catch (Exception ignored) {
            report.put("framework", new JSONObject().put("status", "unavailable"));
        }
        File engine = new File(getApplicationInfo().nativeLibraryDir, "libvowifi_engine.so");
        JSONObject source;
        try (InputStream input = getAssets().open("engine-source.json")) {
            source = new JSONObject(new String(input.readNBytes(8193), StandardCharsets.UTF_8));
        }
        boolean matches = source.getString("engine_sha256").equals(sha(engine));
        report.put("engine_integrity", matches);
        if (!matches) return report.put("engine", new JSONObject().put("error", "engine_integrity_failed"));
        byte[] input = new JSONObject().put("bindings", bindings).toString().getBytes(StandardCharsets.UTF_8);
        java.lang.Process process = new ProcessBuilder(engine.getAbsolutePath(), "root-probe")
            .redirectError(ProcessBuilder.Redirect.to(new File("/dev/null"))).start();
        child = process;
        try {
            try (OutputStream pipe = process.getOutputStream()) { pipe.write(input); }
            Arrays.fill(input, (byte) 0);
            // Native output is bounded by two SIM summaries and contains no identities.
            if (!process.waitFor(35, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                return report.put("engine", new JSONObject().put("error", "engine_probe_timeout"));
            }
            byte[] output = process.getInputStream().readNBytes(8193);
            if (output.length > 8192 || process.exitValue() != 0) {
                return report.put("engine", new JSONObject().put("error", "engine_probe_failed"));
            }
            return report.put("engine", new JSONObject(new String(output, StandardCharsets.UTF_8)));
        } finally {
            Arrays.fill(input, (byte) 0);
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            child = null;
        }
    }

    static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[32768];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        StringBuilder text = new StringBuilder();
        for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private void echo(ParcelFileDescriptor fd) {
        byte[] buffer = new byte[1024];
        int total = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        try {
            while (total < 4096 && android.os.SystemClock.elapsedRealtime() < deadline) {
                streamStatus = "read";
                int count = Os.read(fd.getFileDescriptor(), buffer, 0, Math.min(buffer.length, 4096-total));
                if (count <= 0) break;
                streamStatus = "write";
                for (int sent = 0; sent < count;) {
                    int written = Os.write(fd.getFileDescriptor(), buffer, sent, count-sent);
                    if (written <= 0) throw new java.io.IOException("stream_closed");
                    sent += written;
                }
                total += count;
            }
            streamStatus = total == 4096 ? "completed" : "peer_closed_or_deadline";
        } catch (Exception error) { streamStatus = streamStatus + ":" + failureCode(error); }
        finally {
            try { fd.close(); } catch (Exception ignored) { }
            stream = null;
        }
    }

    static String failureCode(Exception error) {
        if (error instanceof android.system.ErrnoException) return "errno_" + ((android.system.ErrnoException) error).errno;
        if (error instanceof SecurityException) return "permission_denied";
        if (error instanceof java.io.InterruptedIOException) return "io_timeout";
        if (error instanceof java.io.IOException) return "io_failed";
        if (error instanceof IllegalStateException) return "invalid_state";
        return "unavailable";
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        nativeObservation.close();
        runtime.stopOwnedEngines();
        for (RootStream relay : streams.values()) relay.close();
        java.lang.Process process = child;
        if (process != null) process.destroyForcibly();
        ParcelFileDescriptor fd = stream;
        if (fd != null) { try { fd.close(); } catch (Exception ignored) { } }
        super.onDestroy();
    }
}
