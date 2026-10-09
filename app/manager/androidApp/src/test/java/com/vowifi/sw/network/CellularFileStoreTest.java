package com.vowifi.sw.network;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CellularFileStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String CARD = "0123456789";

    @Test public void policySurvivesNewProcessStoreAndHasPrivatePermissions() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("network");
        try (CellularFileStore store = new CellularFileStore(root)) {
            store.save(CARD, new CellularController.Policy(0, true));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(root.resolve(CARD + ".properties")));
        }
        try (CellularFileStore reopened = new CellularFileStore(root)) {
            assertEquals(0, reopened.load(CARD).slot);
            assertTrue(reopened.load(CARD).originalEnabled);
            assertEquals(1, reopened.keys().size());
            reopened.remove(CARD);
            assertNull(reopened.load(CARD));
            assertTrue(reopened.keys().isEmpty());
        }
    }

    @Test public void secondWriterFailsWithoutChangingSavedOriginal() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("network");
        try (CellularFileStore first = new CellularFileStore(root)) {
            first.save(CARD, new CellularController.Policy(0, false));
            try {
                new CellularFileStore(root).close();
                fail("Expected BUSY");
            } catch (CellularController.Failure failure) {
                assertEquals("BUSY", failure.code);
            }
            assertFalse(first.load(CARD).originalEnabled);
        }
        try (CellularFileStore next = new CellularFileStore(root)) {
            assertFalse(next.load(CARD).originalEnabled);
        }
    }

    @Test public void malformedRecordDoesNotInventEnabledOriginal() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("network");
        try (CellularFileStore store = new CellularFileStore(root)) {
            Files.write(root.resolve(CARD + ".properties"),
                "version=1\nslot=0\noriginalEnabled=unknown\n".getBytes(StandardCharsets.US_ASCII));
            try { store.load(CARD); fail("Expected POLICY_INVALID"); }
            catch (CellularController.Failure failure) { assertEquals("POLICY_INVALID", failure.code); }
        }
    }

    @Test public void unrelatedFilesAreNotTreatedAsSimPolicies() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("network");
        try (CellularFileStore store = new CellularFileStore(root)) {
            Files.write(root.resolve("notes.properties"), new byte[]{1});
            Files.write(root.resolve(".pending.tmp"), new byte[]{1});
            assertTrue(store.keys().isEmpty());
        }
    }
}
