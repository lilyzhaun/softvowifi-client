package com.vowifi.sw.manager.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvisioningIdentityMainContractTest {
    @Test
    fun sourceKeepsExactMainNameWithSingleStdoutPrintlnAndNoLog() {
        val source = readRequired("androidApp/src/main/java/com/vowifi/sw/ProvisioningIdentityMain.java")
        val keep = readRequired("androidApp/proguard-rules.pro")

        assertTrue(source.contains("package com.vowifi.sw;"))
        assertTrue(source.contains("public final class ProvisioningIdentityMain"))
        assertTrue(source.contains("public static void main(String[] args)"))
        assertEquals(1, Regex("""System\.out""").findAll(source).count())
        assertTrue(source.contains("System.out.println("))
        assertFalse(source.contains("System.err"))
        assertFalse(source.contains("printStackTrace"))
        assertFalse(source.contains("android.util.Log"))
        assertFalse(Regex("""\bLog\.""").containsMatchIn(source))
        assertFalse(source.contains("ActivityThread"))
        assertFalse(source.contains("System.out.print("))
        assertTrue(source.contains("System.exit(18)"))
        assertTrue(source.contains("iphonesubinfo"))
        assertTrue(source.contains("IPhoneSubInfo\$Stub"))
        assertFalse(source.contains("getIccSerialNumberForSubscriber"))
        val identity = readRequired("androidApp/src/main/java/com/vowifi/sw/ProvisioningImsIdentity.java")
        assertTrue(source.contains("getSubscriberIdForSubscriber"))
        assertTrue(source.contains("getDeviceIdForPhone"))
        assertTrue(identity.contains("getImsPrivateUserIdentity"))
        assertTrue(identity.contains("getIsimDomain"))
        assertTrue(identity.contains("getIsimImpu"))
        assertTrue(source.contains("clearIdentityFields"))
        assertTrue(source.contains("ProvisioningImsIdentity.readFromBinder"))
        assertFalse(identity.contains("System.err"))
        assertFalse(identity.contains("printStackTrace"))
        assertFalse(source.contains("\"--iccid\""))
        assertTrue(source.contains("\"root\""))
        assertTrue(keep.contains("com.vowifi.sw.ProvisioningIdentityMain"))
        assertTrue(keep.contains("public static void main(java.lang.String[])"))
    }

    @Test
    fun sourceNormalizesTemplateIccidAndLeavesHardwareAuthorityToStrictQmiVerify() {
        val source = readRequired("androidApp/src/main/java/com/vowifi/sw/ProvisioningIdentityMain.java")

        assertTrue(source.contains("import com.vowifi.sw.manager.data.IccidNormalizer;"))
        assertEquals(1, Regex("""IccidNormalizer\.INSTANCE\.normalize""").findAll(source).count())
        assertTrue(source.contains("IccidNormalizer.INSTANCE.normalize(template.getString(\"iccid\"))"))
        assertFalse(source.contains("isAsciiDigits(iccid, 15, 22)"))
        assertTrue(source.contains("isAsciiDigits(imsi, 15, 15)"))
        assertTrue(source.contains("isAsciiDigits(imei, 15, 15)"))
        assertTrue(source.contains("template.put(\"iccid\", templateIccid)"))
        assertFalse(source.contains("constantTimeEquals"))
        val firstNormalize = source.indexOf("IccidNormalizer.INSTANCE.normalize")
        val digitCheck = source.indexOf("isAsciiDigits(imsi, 15, 15)")
        val write = source.indexOf("template.put(\"iccid\", templateIccid)")
        assertTrue(firstNormalize >= 0)
        assertTrue(firstNormalize < digitCheck)
        assertTrue(digitCheck < write)
    }

    @Test
    fun inventoryReaderAndDiscoveryNormalizeIccidBeforeJoinCreateCompare() {
        val inventory = readRequired(
            "shared/src/androidMain/kotlin/com/vowifi/sw/manager/data/AndroidSubscriptionInventory.kt",
        )
        val reader = readRequired(
            "shared/src/androidMain/kotlin/com/vowifi/sw/manager/data/AndroidSubscriptionIdentityReader.kt",
        )
        val discovery = readRequired(
            "shared/src/androidMain/kotlin/com/vowifi/sw/manager/data/AndroidSubscriptionDiscovery.kt",
        )

        assertTrue(inventory.contains("IccidNormalizer.normalize(info.iccId)"))
        assertTrue(inventory.contains("fourDigitTail(key)"))
        assertFalse(inventory.contains("info.iccId.orEmpty()"))
        assertTrue(reader.contains("IccidNormalizer.normalize(iccid)"))
        assertTrue(reader.contains("IccidNormalizer.normalize(it.iccId)"))
        assertTrue(reader.contains("iccid = requested"))
        assertTrue(reader.contains("AndroidCarrierId.project(info.carrierId)"))
        assertFalse(reader.contains("operatorName"))
        assertFalse(reader.contains("it.iccId.orEmpty() == iccid"))
        assertTrue(discovery.contains("IccidNormalizer.normalize(configIccid)"))
        assertTrue(discovery.contains("AndroidSubscriptionInventory(context).listActive()"))
        assertTrue(discovery.contains("candidate.key == expected"))
        assertTrue(discovery.indexOf("IccidNormalizer.normalize(configIccid)") < discovery.indexOf("candidate.key == expected"))
        assertFalse(discovery.contains("candidate.iccId.orEmpty() == configIccid"))
    }

    private fun readRequired(relativeFromManager: String): String {
        val file = locate(relativeFromManager)
        assertTrue(file.isFile, "missing $relativeFromManager at ${file.absolutePath}")
        return file.readText()
    }

    private fun locate(relativeFromManager: String): File {
        val here = File("").absoluteFile
        val candidates = listOf(
            File(here, relativeFromManager),
            File(here, "../$relativeFromManager"),
            File(here, "../../$relativeFromManager"),
            File(here, "app/manager/$relativeFromManager"),
        )
        return candidates.firstOrNull(File::isFile) ?: candidates[1]
    }
}
