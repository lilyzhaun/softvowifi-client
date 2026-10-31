package com.vowifi.sw.root;

import android.os.RemoteException;
import android.telephony.SubscriptionInfo;
import android.content.Context;
import android.telephony.ims.ImsManager;
import com.vowifi.sw.RegistrationObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reuses the exported observer callbacks under the Root identity. */
final class RootNativeObservation implements AutoCloseable {
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<RegistrationObserver> observers = new ArrayList<>();

    RootNativeObservation(Context context) { this.context = context; }

    synchronized void observe(INativeRegistrationEvents events) throws Exception {
        stop();
        if (events == null) return;
        for (SubscriptionInfo info : RootTelephony.activeSubscriptions()) {
            try {
                RegistrationObserver observer = new RegistrationObserver(info.getSubscriptionId(), info.getSimSlotIndex(),
                    context.getSystemService(ImsManager.class).getImsMmTelManager(info.getSubscriptionId()), executor, event -> {
                        try { events.onEvent(event); }
                        catch (RemoteException error) { stop(); }
                    });
                if (observer.start()) observers.add(observer);
            } catch (RuntimeException error) {
                // A missing/replaced subscription must not skip the other line.
                android.util.Log.w("SoftVoWiFiObserver", "observer_unavailable_framework");
            }
        }
    }

    private synchronized void stop() {
        for (RegistrationObserver observer : observers) {
            try { observer.close(); }
            catch (RuntimeException error) { android.util.Log.w("SoftVoWiFiRoot", "native_observer_cleanup_unavailable"); }
        }
        observers.clear();
    }

    @Override public void close() { stop(); executor.shutdownNow(); }
}
