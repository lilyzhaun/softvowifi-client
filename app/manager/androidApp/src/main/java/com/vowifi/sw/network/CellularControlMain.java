package com.vowifi.sw.network;

import android.os.Process;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Paths;
import java.util.List;

/** Command-line entry in the module's APK, never an exported Android component. */
public final class CellularControlMain {
    private CellularControlMain() {}

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0) throw new CellularController.Failure("ROOT_REQUIRED");
            if (args.length > 0 && "deny".equals(args[0])) throw new CellularController.Failure("FEATURE_REMOVED");
            boolean all = args.length == 1 && "restore-all".equals(args[0]);
            if (!all && (args.length != 2 || !args[1].matches("[0-9]{10,32}") ||
                !("status".equals(args[0]) || "restore".equals(args[0])))) {
                throw new CellularController.Failure("INVALID_REQUEST");
            }
            String output;
            try (CellularFileStore store = new CellularFileStore(Paths.get("/data/adb/softvowifi/network/cellular"))) {
                CellularController controller = new CellularController(store, new CellularPhone());
                if (all) {
                    List<String> keys = store.keys();
                    if (keys.size() > 32) throw new CellularController.Failure("POLICY_COUNT_INVALID");
                    int failed = 0;
                    for (String key : keys) {
                        try { controller.restore(key); }
                        catch (Exception error) { failed++; }
                    }
                    if (failed != 0) throw new CellularController.Failure("RESTORE_INCOMPLETE");
                    output = "{\"ok\":true,\"restored\":" + keys.size() + "}";
                } else {
                    CellularController.Status status;
                    if ("restore".equals(args[0])) status = controller.restore(args[1]);
                    else status = controller.status(args[1]);
                    output = status.json();
                }
            }
            System.out.println(output);
        } catch (Exception | LinkageError error) {
            Throwable cause = error;
            while (cause instanceof InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
            String code = cause instanceof CellularController.Failure ? ((CellularController.Failure) cause).code :
                cause instanceof NoSuchMethodException || cause instanceof ClassNotFoundException ||
                    cause instanceof LinkageError ? "UNSUPPORTED" :
                cause instanceof IOException ? "STORE_FAILED" : "CONTROL_FAILED";
            // Never print an exception message, command arguments, SIM identity, or configuration.
            System.out.println("{\"ok\":false,\"code\":\"" + code + "\"}");
            System.exit(1);
        }
    }
}
