package com.vowifi.sw;

public interface ProcessRuntimeStarter {
    void startRuntime();
    default void onObserverReady(ObserverService service) {}
    default void onObserverDestroyed(ObserverService service) {}
    default void publishObserverForeground(ObserverService service, int id, android.app.Notification notification) {
        service.startForeground(id, notification);
    }
}
