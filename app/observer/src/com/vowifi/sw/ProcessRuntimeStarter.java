package com.vowifi.sw;

public interface ProcessRuntimeStarter {
    void startRuntime();
    default void onObserverReady(ObserverService service) {}
    default void onObserverDestroyed(ObserverService service) {}
}
