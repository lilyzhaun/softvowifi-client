package com.vowifi.sw.root;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Installs only this APK's exported control scripts into its private Root data directory. */
final class RootRuntime {
    static final String DATA = "/data/adb/softvowifi";
    static final String RUNTIME = DATA + "/runtime";
    static final String SOCKETS = "/data/local/tmp/softvowifi";
    private final Context context;
    private String environment;

    RootRuntime(Context context) { this.context = context; }

    synchronized String prepare() throws Exception {
        if (environment != null) return environment;
        File engine = new File(context.getApplicationInfo().nativeLibraryDir, "libvowifi_engine.so");
        JSONObject source;
        try (InputStream input = context.getAssets().open("engine-source.json")) {
            source = new JSONObject(new String(input.readNBytes(8193), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!source.getString("engine_sha256").equals(RootBackendService.sha(engine)))
            throw new IllegalStateException("engine_integrity_failed");
        for (String dir : new String[]{DATA, RUNTIME, RUNTIME + "/lib", RUNTIME + "/bin", SOCKETS}) {
            Files.createDirectories(new File(dir).toPath());
            Os.chmod(dir, 0700);
        }
        copyAssets("runtime", new File(RUNTIME));
        File link = new File(RUNTIME, "bin/vowifi-engine-arm64");
        Files.deleteIfExists(link.toPath());
        Files.createSymbolicLink(link.toPath(), engine.toPath());
        String stat = new String(Files.readAllBytes(new File("/proc/self/stat").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        String start = stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19];
        environment = "export MODDIR=" + quote(RUNTIME) + " VOWIFI_DATA_DIR=" + quote(DATA)
            + " VOWIFI_SOCKET_DIR=" + quote(SOCKETS) + " ENGINE_BIN=" + quote(engine.getAbsolutePath())
            + " PROVISIONING_IDENTITY_APK=" + quote(context.getApplicationInfo().sourceDir)
            + " VOWIFI_OWNER_PID=" + Process.myPid() + " VOWIFI_OWNER_START=" + quote(start);
        return environment;
    }

    private void copyAssets(String path, File target) throws Exception {
        String[] children = context.getAssets().list(path);
        if (children.length == 0) {
            try (InputStream input = context.getAssets().open(path)) {
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Os.chmod(target.getAbsolutePath(), path.endsWith(".sh") ? 0700 : 0600);
            return;
        }
        Files.createDirectories(target.toPath());
        for (String name : children) copyAssets(path + "/" + name, new File(target, name));
    }

    void stopOwnedEngines() {
        if (environment == null) return;
        File[] configurations = new File(DATA, "config").listFiles();
        if (configurations == null) return;
        for (File config : configurations) {
            if (!config.getName().matches("[0-9]{10,32}\\.json")) continue;
            String iccid = config.getName().replace(".json", "");
            try {
                java.lang.Process stop = new ProcessBuilder("/system/bin/sh", "-c",
                    environment + "\n" + RUNTIME + "/control.sh stop " + iccid)
                    .redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null")).start();
                if (!stop.waitFor(15, TimeUnit.SECONDS)) stop.destroyForcibly();
            } catch (java.io.IOException error) { android.util.Log.w("SoftVoWiFiRoot", "stop_command_unavailable"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
