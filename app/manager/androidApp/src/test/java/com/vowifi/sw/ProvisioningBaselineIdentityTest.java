package com.vowifi.sw;

import android.telephony.SubscriptionInfo;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = android.app.Application.class)
public final class ProvisioningBaselineIdentityTest {
    private static final String ICCID = "0000000000000000001";

    private static JSONObject fresh(JSONObject template, SubscriptionInfo info, int subId, int slot) throws Exception {
        Class<?> type = Class.forName("com.vowifi.sw.ProvisioningBaselineIdentity");
        Method method = type.getDeclaredMethod("template", JSONObject.class, SubscriptionInfo.class, int.class, int.class);
        method.setAccessible(true);
        return (JSONObject) method.invoke(null, template, info, subId, slot);
    }

    private static SubscriptionInfo info(int id, int slot, String iccid, String mcc, String mnc) {
        return SubscriptionInfoBuilder.newBuilder().setId(id).setSimSlotIndex(slot).setIccId(iccid)
            .setMcc(mcc).setMnc(mnc).buildSubscriptionInfo();
    }

    @Test public void freshTemplateUsesCurrentSubscriptionNotCallerOverridesOrPlmn() throws Exception {
        JSONObject old = new JSONObject("{\"iccid\":\"" + ICCID + "\",\"slot\":0,\"mcc\":\"999\",\"mnc\":\"99\",\"transport\":\"sim2\",\"dnsOverrides\":{\"wifi\":\"192.0.2.53:53\"},\"imsRealm\":\"old.example.test\",\"smsc\":\"+15550000001\",\"configVersion\":1,\"configRevision\":\"old\"}");
        String untouched = old.toString();
        JSONObject fresh = fresh(old, info(54, 0, ICCID + "F", "310", "026"), 54, 0);
        assertEquals("310", fresh.getString("mcc"));
        assertEquals("026", fresh.getString("mnc"));
        assertEquals(3, fresh.getInt("mncLen"));
        assertEquals(ICCID, fresh.getString("iccid"));
        assertEquals(0, fresh.getInt("slot"));
        assertEquals("wifi", fresh.getString("transport"));
        assertTrue(fresh.getBoolean("enabled"));
        for (String key : new String[] {"dns", "dnsOverrides", "smsc", "imsRealm", "epdgFqdn", "configVersion", "configRevision"}) {
            assertFalse(key, fresh.has(key));
        }
        assertEquals(untouched, old.toString());
    }

    @Test public void missingMovedOrDifferentSubscriptionFailsClosed() throws Exception {
        JSONObject target = new JSONObject("{\"iccid\":\"" + ICCID + "\",\"slot\":0}");
        SubscriptionInfo[] cases = {
            null,
            info(55, 0, ICCID, "310", "260"),
            info(54, 1, ICCID, "310", "260"),
            info(54, 0, "0000000000000000002", "310", "260"),
            info(54, 0, ICCID, "", "260"),
            info(54, 0, ICCID, "310", ""),
        };
        for (SubscriptionInfo candidate : cases) {
            try {
                fresh(target, candidate, 54, 0);
                fail("stale/unreadable current subscription accepted");
            } catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
            }
        }
    }

    @Test public void retirementRequiresKnownInventoryAndExcludesEveryActiveCard() throws Exception {
        Class<?> type = Class.forName("com.vowifi.sw.ProvisioningBaselineIdentity");
        Method check = type.getDeclaredMethod("requireInactive", List.class, String.class);
        check.setAccessible(true);
        check.invoke(null, Arrays.asList(info(54, 0, "0000000000000000002", "310", "260")), ICCID);
        check.invoke(null, java.util.Collections.emptyList(), ICCID);
        for (List<SubscriptionInfo> active : Arrays.<List<SubscriptionInfo>>asList(null,
            Arrays.asList(info(54, 0, ICCID + "F", "310", "260")),
            Arrays.asList(info(54, 0, "", "310", "260")), Arrays.asList((SubscriptionInfo) null))) {
            try {
                check.invoke(null, active, ICCID);
                fail("active or unreadable inventory authorized retirement");
            } catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
            }
        }
    }
}
