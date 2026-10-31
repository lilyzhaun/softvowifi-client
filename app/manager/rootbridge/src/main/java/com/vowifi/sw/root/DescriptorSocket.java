package com.vowifi.sw.root;

import android.net.LocalSocket;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public-API adapter for the existing LocalSocket consumers; the FD is created by the App. */
final class DescriptorSocket extends LocalSocket {
    private final ParcelFileDescriptor descriptor;
    private final InputStream input;
    private final OutputStream output;
    private final AtomicBoolean closed = new AtomicBoolean();

    DescriptorSocket(ParcelFileDescriptor descriptor) {
        this.descriptor = descriptor;
        input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
    }

    @Override public InputStream getInputStream() { return input; }
    @Override public OutputStream getOutputStream() { return output; }
    @Override public void setSoTimeout(int millis) throws IOException {
        try {
            Os.setsockoptTimeval(descriptor.getFileDescriptor(), OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(millis));
            Os.setsockoptTimeval(descriptor.getFileDescriptor(), OsConstants.SOL_SOCKET,
                OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(millis));
        } catch (ErrnoException error) { throw new IOException(error); }
    }
    @Override public void shutdownInput() throws IOException { shutdown(OsConstants.SHUT_RD); }
    @Override public void shutdownOutput() throws IOException { shutdown(OsConstants.SHUT_WR); }
    private void shutdown(int how) throws IOException {
        try { Os.shutdown(descriptor.getFileDescriptor(), how); }
        catch (ErrnoException error) { throw new IOException(error); }
    }
    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try { shutdown(OsConstants.SHUT_RDWR); }
        finally { descriptor.close(); }
    }
}
