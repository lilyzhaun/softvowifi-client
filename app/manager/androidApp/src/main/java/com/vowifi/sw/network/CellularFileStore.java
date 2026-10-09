package com.vowifi.sw.network;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Private module data, not application preferences or engine configuration. */
final class CellularFileStore implements CellularController.Store, AutoCloseable {
    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;

    CellularFileStore(Path root) throws Exception {
        this.root = root;
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path lockPath = root.resolve(".lock");
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        boolean ready = false;
        try {
            Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException error) { throw new CellularController.Failure("BUSY"); }
            if (lock == null) throw new CellularController.Failure("BUSY");
            ready = true;
        } finally {
            if (!ready) channel.close();
        }
    }

    public CellularController.Policy load(String key) throws Exception {
        Path path = file(key);
        if (!Files.exists(path)) return null;
        if (Files.size(path) > 4096) throw new CellularController.Failure("POLICY_INVALID");
        Properties values = new Properties();
        values.load(new ByteArrayInputStream(Files.readAllBytes(path)));
        String original = values.getProperty("originalEnabled");
        String slot = values.getProperty("slot", "");
        if (!"1".equals(values.getProperty("version")) ||
            !("true".equals(original) || "false".equals(original)) || !slot.matches("[0-7]")) {
            throw new CellularController.Failure("POLICY_INVALID");
        }
        return new CellularController.Policy(Integer.parseInt(slot), Boolean.parseBoolean(original));
    }

    public void save(String key, CellularController.Policy policy) throws Exception {
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("slot", Integer.toString(policy.slot));
        values.setProperty("originalEnabled", Boolean.toString(policy.originalEnabled));
        Path temporary = Files.createTempFile(root, ".policy-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                values.store(output, null);
                output.getFD().sync();
            }
            Files.move(temporary, file(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void remove(String key) throws Exception {
        Files.deleteIfExists(file(key));
        syncDirectory();
    }

    List<String> keys() throws Exception {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, "*.properties")) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                String key = name.substring(0, name.length() - ".properties".length());
                if (key.matches("[0-9]{10,32}")) result.add(key);
            }
        }
        return result;
    }

    private Path file(String key) { return root.resolve(key + ".properties"); }

    private void syncDirectory() throws Exception {
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    public void close() throws Exception {
        try { lock.release(); } finally { channel.close(); }
    }
}
