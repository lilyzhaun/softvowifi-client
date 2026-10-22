package com.vowifi.sw;

import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.RegistrationManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.Executor;

final class RegistrationObserver implements AutoCloseable {
    private static final String TAG = "SoftVoWiFiObserver";

    private final int subId;
    private final int slot;
    private final ImsMmTelManager manager;
    private final Executor executor;
    private final File output;
    private final RegistrationManager.RegistrationCallback callback =
            new RegistrationManager.RegistrationCallback() {
                @Override
                public void onRegistered(ImsRegistrationAttributes attributes) {
                    JSONObject event = baseEvent("REGISTERED");
                    try {
                        event.put("transportType", attributes.getTransportType());
                        event.put("attributeFlags", attributes.getAttributeFlags());
                        event.put("featureTags", new JSONArray(attributes.getFeatureTags()));
                    } catch (JSONException exception) {
                        Log.e(TAG, "observer_registered_encoding_failed");
                        return;
                    }
                    write(event);
                }

                @Override
                public void onRegistering(ImsRegistrationAttributes attributes) {
                    JSONObject event = baseEvent("REGISTERING");
                    try {
                        event.put("transportType", attributes.getTransportType());
                        event.put("attributeFlags", attributes.getAttributeFlags());
                        event.put("featureTags", new JSONArray(attributes.getFeatureTags()));
                    } catch (JSONException exception) {
                        Log.e(TAG, "observer_registering_encoding_failed");
                        return;
                    }
                    write(event);
                }

                @Override
                public void onUnregistered(ImsReasonInfo info) {
                    JSONObject event = baseEvent("UNREGISTERED");
                    try {
                        event.put("reasonCode", info.getCode());
                        event.put("extraCode", info.getExtraCode());
                    } catch (JSONException exception) {
                        Log.e(TAG, "observer_unregistered_encoding_failed");
                        return;
                    }
                    write(event);
                }
            };

    RegistrationObserver(
            int subId,
            int slot,
            ImsMmTelManager manager,
            Executor executor,
            File output) {
        this.subId = subId;
        this.slot = slot;
        this.manager = manager;
        this.executor = executor;
        this.output = output;
    }

    boolean start() {
        try {
            manager.registerImsRegistrationCallback(executor, callback);
            Log.i(TAG, "observer_callback_registered");
            return true;
        } catch (ImsException | RuntimeException exception) {
            Log.w(TAG, "observer_callback_unavailable");
            return false;
        }
    }

    @Override
    public void close() {
        manager.unregisterImsRegistrationCallback(callback);
    }

    private JSONObject baseEvent(String state) {
        JSONObject event = new JSONObject();
        try {
            event.put("timestampMs", System.currentTimeMillis());
            event.put("subId", subId);
            if (slot >= 0 && slot <= 1) event.put("slot", slot);
            event.put("state", state);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to encode base event", exception);
        }
        return event;
    }

    private void write(JSONObject event) {
        byte[] bytes = (event.toString() + '\n').getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) return;
        // Both SIM callbacks share one file and one bound, including truncation.
        synchronized (RegistrationObserver.class) {
            try {
                Files.createDirectories(output.toPath().getParent(),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                try (FileChannel stream = FileChannel.open(output.toPath(), java.util.Set.of(
                        StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                    if (stream.size() + bytes.length > 1024 * 1024) stream.truncate(0);
                    stream.position(stream.size());
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        if (stream.write(buffer) <= 0) throw new IOException("observer short write");
                    }
                }
            } catch (IOException exception) {
                Log.e(TAG, "observer_output_unavailable");
            }
        }
    }
}
