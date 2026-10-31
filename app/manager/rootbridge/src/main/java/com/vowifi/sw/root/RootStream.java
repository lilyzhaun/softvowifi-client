package com.vowifi.sw.root;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Byte-transparent bridge; all protocol, message and audio ownership remains in the manager. */
final class RootStream implements AutoCloseable {
    private final ParcelFileDescriptor descriptor;
    private final LocalSocket app;
    private final LocalSocket engine = new LocalSocket();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Runnable onClose;

    RootStream(String iccid, boolean audio, ParcelFileDescriptor source, Runnable onClose) throws Exception {
        if (iccid == null || !iccid.matches("[0-9]{10,32}") || source == null
            || !OsConstants.S_ISSOCK(Os.fstat(source.getFileDescriptor()).st_mode))
            throw new IllegalArgumentException("invalid_engine_stream");
        this.onClose = onClose;
        descriptor = ParcelFileDescriptor.dup(source.getFileDescriptor());
        app = new DescriptorSocket(descriptor);
        try {
            engine.connect(new LocalSocketAddress(RootRuntime.SOCKETS + "/" + iccid
                + (audio ? ".audio.sock" : ".sock"), LocalSocketAddress.Namespace.FILESYSTEM));
        } catch (IOException error) { close(); throw error; }
    }

    void start() throws IOException {
        InputStream appInput = app.getInputStream(), engineInput = engine.getInputStream();
        OutputStream appOutput = app.getOutputStream(), engineOutput = engine.getOutputStream();
        new Thread(() -> copy(appInput, engineOutput), "root-stream-out").start();
        new Thread(() -> copy(engineInput, appOutput), "root-stream-in").start();
    }

    private void copy(InputStream input, OutputStream output) {
        byte[] buffer = new byte[8192];
        try { for (int size; (size = input.read(buffer)) >= 0;) output.write(buffer, 0, size); }
        catch (IOException ignored) { /* Disconnect terminates both directions and reaches the existing client. */ }
        finally { close(); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (LocalSocket socket : new LocalSocket[]{app, engine}) {
            try { socket.shutdownInput(); } catch (IOException ignored) { }
            try { socket.shutdownOutput(); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
        try { descriptor.close(); } catch (IOException ignored) { }
        onClose.run();
    }
}
