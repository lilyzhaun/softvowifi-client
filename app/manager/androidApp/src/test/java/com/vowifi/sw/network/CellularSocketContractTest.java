package com.vowifi.sw.network;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public class CellularSocketContractTest {
    @Test public void timeoutMustFollowAndroidLocalSocketCreation() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(System.getProperty("cellular.source.directory"),
            "CellularPhone.java")), StandardCharsets.UTF_8);
        int connect = source.indexOf("socket.connect(");
        int timeout = source.indexOf("socket.setSoTimeout(");
        // AOSP LocalSocket.connect creates the fd; setSoTimeout does not create it.
        assertTrue(connect >= 0 && timeout > connect);
    }
}
