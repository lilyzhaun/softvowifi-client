package com.vowifi.sw.root;
import android.os.ParcelFileDescriptor;
import android.telephony.SubscriptionInfo;
import com.vowifi.sw.root.INativeRegistrationEvents;

interface IRootBackend {
    String runReadOnlyChecks();
    void attachProbeStream(in ParcelFileDescriptor descriptor);
    String getProbeStreamStatus();
    List<SubscriptionInfo> activeSubscriptions();
    String subscriptionExtras(int subId);
    String nativeStatus();
    boolean nativeCallsIdle();
    void observeNativeRegistration(INativeRegistrationEvents events);
    String prepareRuntime();
    void attachEngineStream(String iccid, boolean audio, in ParcelFileDescriptor descriptor);
}
