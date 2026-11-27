package com.vowifi.sw.root;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeCallGateMainTest {
    @Test public void onlyActualRootAndConfirmedIdleAllowAdmission() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, NativeCallGateMain.query(0, () -> true, new PrintStream(output)));
        assertEquals("{\"ok\":true,\"idle\":true}\n", output.toString(StandardCharsets.UTF_8));
        output.reset();
        assertEquals(1, NativeCallGateMain.query(0, () -> false, new PrintStream(output)));
        assertEquals("{\"ok\":true,\"idle\":false}\n", output.toString(StandardCharsets.UTF_8));
        output.reset();
        assertEquals(2, NativeCallGateMain.query(10001, () -> { fail("non-root queried telephony"); return true; }, new PrintStream(output)));
        assertEquals("{\"ok\":false,\"code\":\"UNAVAILABLE\"}\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test public void missingOrDeniedReadNeverBecomesIdleOrLeaksException() {
        List<Callable<Boolean>> reads = List.of(
            () -> null,
            () -> { throw new SecurityException("private subscription details"); },
            () -> { throw new ReflectiveOperationException("private framework details"); }
        );
        for (Callable<Boolean> read : reads) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertEquals(2, NativeCallGateMain.query(0, read, new PrintStream(output)));
            assertEquals("{\"ok\":false,\"code\":\"UNAVAILABLE\"}\n", output.toString(StandardCharsets.UTF_8));
        }
    }
}
