package com.vowifi.sw;

import java.lang.reflect.InvocationTargetException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = android.app.Application.class)
public final class ProvisioningImsIdentityTest {
    private static final String IMPI = "private.user@home.card.test";
    private static final String HOME = "home.card.test";
    private static final String SIP = "sip:public.user@public.card.test";

    @Test
    public void classify_fullValidBundle_isAvailableWithoutRebuildingImpu() {
        ProvisioningImsIdentity.Bundle bundle = ProvisioningImsIdentity.classify(
            ProvisioningImsIdentity.Field.value(IMPI),
            ProvisioningImsIdentity.Field.value(HOME),
            ProvisioningImsIdentity.Field.fromImpuList(new String[] {SIP})
        );

        assertEquals(ProvisioningImsIdentity.AVAILABLE, bundle.status);
        assertEquals(IMPI, bundle.impi);
        assertEquals(SIP, bundle.sipImpu);
        assertEquals(HOME, bundle.imsHomeDomain);
        assertFalse(bundle.sipImpu.contains("310260"));
    }

    @Test
    public void classify_allEmpty_isEmptyNotDenied() {
        ProvisioningImsIdentity.Bundle bundle = ProvisioningImsIdentity.classify(
            ProvisioningImsIdentity.Field.empty(),
            ProvisioningImsIdentity.Field.value("  "),
            ProvisioningImsIdentity.Field.fromImpuList(new String[0])
        );

        assertEquals(ProvisioningImsIdentity.EMPTY, bundle.status);
        assertEquals("", bundle.impi);
        assertEquals("", bundle.sipImpu);
        assertEquals("", bundle.imsHomeDomain);
    }

    @Test
    public void classify_missingMethod_isUnsupported() {
        ProvisioningImsIdentity.Bundle bundle = ProvisioningImsIdentity.classify(
            ProvisioningImsIdentity.Field.fromInvoke(new NoSuchMethodException("getIsimDomain")),
            ProvisioningImsIdentity.Field.value(HOME),
            ProvisioningImsIdentity.Field.value(SIP)
        );

        assertEquals(ProvisioningImsIdentity.UNSUPPORTED, bundle.status);
        assertEquals("", bundle.impi);
        assertEquals("", bundle.sipImpu);
        assertEquals("", bundle.imsHomeDomain);
    }

    @Test
    public void classify_securityException_isDeniedNotEmpty() {
        InvocationTargetException denied = new InvocationTargetException(new SecurityException("binder"));
        ProvisioningImsIdentity.Bundle bundle = ProvisioningImsIdentity.classify(
            ProvisioningImsIdentity.Field.fromInvoke(denied),
            ProvisioningImsIdentity.Field.empty(),
            ProvisioningImsIdentity.Field.empty()
        );

        assertEquals(ProvisioningImsIdentity.DENIED, bundle.status);
        assertEquals("", bundle.impi);
        assertFalse(ProvisioningImsIdentity.EMPTY.equals(bundle.status));
    }

    @Test
    public void classify_partialBundle_isInvalidAndClearsValues() {
        ProvisioningImsIdentity.Bundle bundle = ProvisioningImsIdentity.classify(
            ProvisioningImsIdentity.Field.value(IMPI),
            ProvisioningImsIdentity.Field.empty(),
            ProvisioningImsIdentity.Field.value(SIP)
        );

        assertEquals(ProvisioningImsIdentity.INVALID, bundle.status);
        assertEquals("", bundle.impi);
        assertEquals("", bundle.sipImpu);
        assertEquals("", bundle.imsHomeDomain);
    }

    @Test
    public void classify_malformedImpiOrTelOnlyImpu_isInvalid() {
        ProvisioningImsIdentity.Bundle malformedImpi = ProvisioningImsIdentity.classify(
            ProvisioningImsIdentity.Field.value("not-an-impi"),
            ProvisioningImsIdentity.Field.value(HOME),
            ProvisioningImsIdentity.Field.value(SIP)
        );
        ProvisioningImsIdentity.Bundle telOnly = ProvisioningImsIdentity.classify(
            ProvisioningImsIdentity.Field.value(IMPI),
            ProvisioningImsIdentity.Field.value(HOME),
            ProvisioningImsIdentity.Field.fromImpuList(new String[] {"tel:+15550000001"})
        );

        assertEquals(ProvisioningImsIdentity.INVALID, malformedImpi.status);
        assertEquals(ProvisioningImsIdentity.INVALID, telOnly.status);
        assertEquals("", telOnly.sipImpu);
    }

    @Test
    public void fromImpuList_selectsFirstValidSipAndPreservesHostUri() {
        ProvisioningImsIdentity.Field field = ProvisioningImsIdentity.Field.fromImpuList(new String[] {
            "tel:+15550000001",
            "  ",
            "sip:public.user@public.card.test;user=phone",
            "sip:second.user@other.card.test",
        });

        assertEquals(ProvisioningImsIdentity.Kind.VALUE, field.kind);
        assertEquals("sip:public.user@public.card.test;user=phone", field.value);
        assertTrue(field.value.contains("public.card.test"));
        assertFalse(field.value.contains("310260000000001"));
    }

    @Test
    public void fromInvoke_doesNotTreatDeniedAsUnsupported() {
        ProvisioningImsIdentity.Field denied = ProvisioningImsIdentity.Field.fromInvoke(
            new InvocationTargetException(new SecurityException())
        );
        ProvisioningImsIdentity.Field missing = ProvisioningImsIdentity.Field.fromInvoke(
            new NoSuchMethodException()
        );

        assertEquals(ProvisioningImsIdentity.Kind.DENIED, denied.kind);
        assertEquals(ProvisioningImsIdentity.Kind.UNSUPPORTED, missing.kind);
    }

    @Test
    public void isIdentityMode_onlyExactThirdArg() {
        assertTrue(ProvisioningImsIdentity.isIdentityMode(new String[] {"2", "1", "identity"}));
        assertFalse(ProvisioningImsIdentity.isIdentityMode(new String[] {"2", "1"}));
        assertFalse(ProvisioningImsIdentity.isIdentityMode(new String[] {"2", "1", "create"}));
    }
}
