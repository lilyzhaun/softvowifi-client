package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SubscriptionDiscovery

fun sanitizeCarrierLabel(carrierName: String?, displayName: String?): String {
    val cleanedCarrier = cleanedLabel(carrierName)
    if (isSafeLabel(cleanedCarrier)) {
        return cleanedCarrier.take(MAX_CARRIER_LABEL_CHARS)
    }
    val cleanedDisplay = cleanedLabel(displayName)
    if (isSafeLabel(cleanedDisplay)) {
        return cleanedDisplay.take(MAX_CARRIER_LABEL_CHARS)
    }
    return FALLBACK_CARRIER_LABEL
}

fun validatedMcc(raw: String?): String? = raw?.takeIf(MCC_PATTERN::matches)

fun validatedMnc(raw: String?): String? = raw?.takeIf(MNC_PATTERN::matches)

fun validatedSlotIndex(slotIndex: Int): Int? = slotIndex.takeIf { it in VALID_SLOT_RANGE }

fun matchedSubscription(
    carrierName: String?,
    displayName: String?,
    slotIndex: Int,
    mcc: String?,
    mnc: String?,
): SubscriptionDiscovery.Matched = SubscriptionDiscovery.Matched(
    carrierLabel = sanitizeCarrierLabel(carrierName, displayName),
    slotIndex = validatedSlotIndex(slotIndex),
    mcc = validatedMcc(mcc),
    mnc = validatedMnc(mnc),
)

fun systemSubscriptionSummary(discovery: SubscriptionDiscovery): String? = when (discovery) {
    SubscriptionDiscovery.NotAttempted -> null
    SubscriptionDiscovery.Missing -> MISSING_SUMMARY
    SubscriptionDiscovery.Unavailable -> UNAVAILABLE_SUMMARY
    is SubscriptionDiscovery.Matched -> matchedSummary(discovery)
}

private fun matchedSummary(matched: SubscriptionDiscovery.Matched): String = listOfNotNull(
    matched.slotIndex?.let(::validatedSlotIndex)?.let { "SIM ${it + 1}" },
    matched.carrierLabel,
    plmnSegment(matched.mcc, matched.mnc),
).joinToString(SUMMARY_SEPARATOR)

private fun plmnSegment(mcc: String?, mnc: String?): String? {
    val validMcc = validatedMcc(mcc) ?: return null
    val validMnc = validatedMnc(mnc) ?: return null
    return "$validMcc / $validMnc"
}

private fun cleanedLabel(raw: String?): String {
    if (raw == null) return ""
    return raw.filterNot { ch -> ch.code in CONTROL_RANGE || ch.code == DELETE_CHAR }.trim()
}

private fun isSafeLabel(cleaned: String): Boolean =
    cleaned.isNotEmpty() && !containsIdentityDigits(cleaned)

private const val FALLBACK_CARRIER_LABEL = "已匹配订阅"
private const val MISSING_SUMMARY = "缺少元数据"
private const val UNAVAILABLE_SUMMARY = "不可用"
private const val SUMMARY_SEPARATOR = " · "
private const val MAX_CARRIER_LABEL_CHARS = 32
private const val DELETE_CHAR = 0x7F
private val CONTROL_RANGE = 0x00..0x1F
private val VALID_SLOT_RANGE = 0..7
private val MCC_PATTERN = Regex("""^\d{3}$""")
private val MNC_PATTERN = Regex("""^\d{2,3}$""")
