package com.vowifi.sw;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ObserverService extends Service {
    private static final String TAG = "SoftVoWiFiObserver";
    private static final String CHANNEL_ID = "ims_observer";
    private static final int NOTIFICATION_ID = 1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<Integer, RegistrationObserver> observers = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        publishForeground();
        com.vowifi.sw.root.RootBridge.bind(this, () -> {
            if (getApplication() instanceof ProcessRuntimeStarter) {
                ((ProcessRuntimeStarter) getApplication()).startRuntime();
            }
            registerObservers();
        });
        if (getApplication() instanceof ProcessRuntimeStarter) {
            ((ProcessRuntimeStarter) getApplication()).onObserverReady(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (getApplication() instanceof ProcessRuntimeStarter) {
            ((ProcessRuntimeStarter) getApplication()).startRuntime();
            ((ProcessRuntimeStarter) getApplication()).onObserverReady(this);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try { com.vowifi.sw.root.RootBridge.observeNativeRegistration(null); }
        catch (RuntimeException exception) { Log.w(TAG, "observer_cleanup_unavailable"); }
        if (getApplication() instanceof ProcessRuntimeStarter) {
            ((ProcessRuntimeStarter) getApplication()).onObserverDestroyed(this);
        }
        try {
            for (RegistrationObserver observer : observers.values()) {
                try {
                    observer.close();
                } catch (RuntimeException exception) {
                    Log.w(TAG, "observer_cleanup_unavailable");
                }
            }
        } finally {
            observers.clear();
            executor.shutdownNow();
            super.onDestroy();
        }
    }

    private Notification createNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.observer_channel), NotificationManager.IMPORTANCE_LOW);
        }
        channel.setBlockable(true);
        manager.createNotificationChannel(channel);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.observer_running))
                .setOngoing(true)
                .build();
    }

    public void restoreObserverNotification() {
        publishForeground();
    }

    private void publishForeground() {
        Notification notification = createNotification();
        if (getApplication() instanceof ProcessRuntimeStarter) {
            ((ProcessRuntimeStarter) getApplication()).publishObserverForeground(this, NOTIFICATION_ID, notification);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void registerObservers() {
        try {
            File output = new File(getFilesDir(), "logs/ims-registration.jsonl");
            com.vowifi.sw.root.RootBridge.observeNativeRegistration(event -> RegistrationObserver.writeEvent(output, event));
        } catch (SecurityException exception) {
            Log.w(TAG, "observer_unavailable_permission_race");
        } catch (RuntimeException exception) {
            Log.w(TAG, "observer_unavailable_framework");
        }
    }
}
