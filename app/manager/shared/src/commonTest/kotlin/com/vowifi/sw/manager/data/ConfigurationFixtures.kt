package com.vowifi.sw.manager.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val FAKE_INSTANCE_ID = "c1"
internal const val FAKE_DISPLAY_LABEL = "实例 •••• 1234"
internal const val FAKE_MCC = "001"
internal const val FAKE_MNC = "01"
internal const val FAKE_SMSC = "+155501"
internal const val FAKE_E164_SMSC = "+155500000000001"
internal const val FAKE_EPDG = "epdg.example.invalid"
internal const val FAKE_REALM = "ims.example.invalid"
internal const val FAKE_DNS = "192.0.2.53:53"
internal const val FAKE_RESOLVED_EPDG = "epdg.epc.mnc001.mcc001.pub.3gppnetwork.org"
internal const val FAKE_RESOLVED_REALM = "ims.mnc001.mcc001.3gppnetwork.org"
internal const val FAKE_DIGIT_RUN = "99999999999991234"

internal fun redactedShowJson(
    ok: Boolean = true,
    displayLabel: String = FAKE_DISPLAY_LABEL,
    lifecycle: String = "stopped",
    transport: String = "wifi",
    smsc: String = FAKE_SMSC,
    epdgFqdn: String = "",
    imsRealm: String = "",
    dns: String = FAKE_DNS,
    resolvedEpdgFqdn: String = FAKE_RESOLVED_EPDG,
    resolvedImsRealm: String = FAKE_RESOLVED_REALM,
    mcc: String = FAKE_MCC,
    mnc: String = FAKE_MNC,
    includeOk: Boolean = true,
    extra: JsonObject = buildJsonObject {},
): String {
    val body = buildJsonObject {
        put("formatState", "current")
        put("configVersion", 1)
        put("configRevision", "r1_abcdefghijklmnopabcdefghijklmnop")
        if (includeOk) put("ok", ok)
        put("displayLabel", displayLabel)
        put("lifecycle", lifecycle)
        put("transport", transport)
        put("smsc", smsc)
        put("epdgFqdn", epdgFqdn)
        put("imsRealm", imsRealm)
        put("dns", dns)
        put("dnsOverrides", buildJsonObject { if (dns.isNotEmpty()) put(transport, dns) })
        put("resolvedEpdgFqdn", resolvedEpdgFqdn)
        put("resolvedImsRealm", resolvedImsRealm)
        put("mcc", mcc)
        put("mnc", mnc)
        extra.forEach { (key, value) -> put(key, value) }
    }
    return Json.encodeToString(JsonObject.serializer(), body)
}
