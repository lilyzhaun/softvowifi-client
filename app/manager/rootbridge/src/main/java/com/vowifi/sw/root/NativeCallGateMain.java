package com.vowifi.sw.root;

import android.os.Process;
import java.io.PrintStream;
import java.util.concurrent.Callable;

/** Root maintenance read only. A negative result never grants or modifies telephony access. */
public final class NativeCallGateMain {
    private NativeCallGateMain() { }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.out.println("{\"ok\":false,\"code\":\"UNAVAILABLE\"}");
            System.exit(2);
        }
        System.exit(query(Process.myUid(), RootTelephony::nativeCallsIdle, System.out));
    }

    static int query(int uid, Callable<Boolean> read, PrintStream out) {
        if (uid != 0) {
            out.println("{\"ok\":false,\"code\":\"UNAVAILABLE\"}");
            return 2;
        }
        final Boolean idle;
        try {
            idle = read.call();
        } catch (Exception error) {
            // The command boundary deliberately exposes no framework exception or subscription data.
            out.println("{\"ok\":false,\"code\":\"UNAVAILABLE\"}");
            return 2;
        }
        if (idle == null) {
            out.println("{\"ok\":false,\"code\":\"UNAVAILABLE\"}");
            return 2;
        }
        out.println(idle ? "{\"ok\":true,\"idle\":true}" : "{\"ok\":true,\"idle\":false}");
        return idle ? 0 : 1;
    }
}
