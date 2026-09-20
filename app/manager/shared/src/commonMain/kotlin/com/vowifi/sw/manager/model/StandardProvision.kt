package com.vowifi.sw.manager.model

data class StandardPublicProfile(
    val mcc: String,
    val mnc: String,
    val mncLen: Int,
    val epdgFqdn: String,
    val imsRealm: String,
    val smsc: String,
    val transport: ConfigurationTransport,
    val dns: String,
    val enabled: Boolean,
)

object StandardProvision {
    fun profileFor(mcc: String?, mnc: String?): StandardPublicProfile? {
        if (mcc == null || mnc == null) return null
        if (!isAsciiDigits(mcc, MCC_LEN)) return null
        if (!isAsciiMnc(mnc)) return null
        val paddedMnc = mnc.padStart(PADDED_MNC_LEN, '0')
        return StandardPublicProfile(
            mcc = mcc,
            mnc = mnc,
            mncLen = mnc.length,
            epdgFqdn = "epdg.epc.mnc$paddedMnc.mcc$mcc.pub.3gppnetwork.org",
            imsRealm = "ims.mnc$paddedMnc.mcc$mcc.3gppnetwork.org",
            smsc = "",
            transport = ConfigurationTransport.WIFI,
            dns = "",
            enabled = true,
        )
    }

    fun automaticProfileFor(mcc: String?, mnc: String?): StandardPublicProfile? =
        profileFor(mcc, mnc)?.copy(epdgFqdn = "")
}

fun resolvedSmsc(androidSmsc: String?, fallbackSmsc: String): String {
    val candidate = androidSmsc?.trim().orEmpty()
    return candidate.takeIf(::isValidAndroidSmsc) ?: fallbackSmsc
}

fun resolvedLine1(androidLine1: String?): String? =
    androidLine1?.trim()?.takeIf(String::isNotEmpty)

private fun isValidAndroidSmsc(value: String): Boolean =
    value.startsWith("+") && value.length > 1 && value.substring(1).all(::isAsciiDigit)

private fun isAsciiMnc(value: String): Boolean =
    value.length in MNC_LEN_MIN..MNC_LEN_MAX && value.all(::isAsciiDigit)

private fun isAsciiDigits(value: String, length: Int): Boolean =
    value.length == length && value.all(::isAsciiDigit)

private fun isAsciiDigit(ch: Char): Boolean = ch in '0'..'9'

private const val MCC_LEN = 3
private const val MNC_LEN_MIN = 2
private const val MNC_LEN_MAX = 3
private const val PADDED_MNC_LEN = 3
