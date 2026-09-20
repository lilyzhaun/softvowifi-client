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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

final class RegistrationObserver implements AutoCloseable {
    private static final String TAG = "SoftVoWiFiObserver";

    private final int subId;
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
            ImsMmTelManager manager,
            Executor executor,
            File output) {
        this.subId = subId;
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
            event.put("state", state);
        } catch (JSONException exception) {
            throw new IllegalStateException("Unable to encode base event", exception);
        }
        return event;
    }

    private synchronized void write(JSONObject event) {
        String line = event.toString();
        try (FileOutputStream stream = new FileOutputStream(output, true)) {
            stream.write(line.getBytes(StandardCharsets.UTF_8));
            stream.write('\n');
        } catch (IOException exception) {
            Log.e(TAG, "observer_output_unavailable");
        }
    }
}
