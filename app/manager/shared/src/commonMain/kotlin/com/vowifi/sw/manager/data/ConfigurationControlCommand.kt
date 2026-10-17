package com.vowifi.sw.manager.data

internal object ConfigurationControlCommand {
    const val SCRIPT = "/data/adb/modules/vowifi_sw/control.sh"
    const val APPLY_TIMEOUT_SECONDS = 60L
    const val CREATE_TIMEOUT_SECONDS = 60L

    fun listLine(): String = "$SCRIPT list"

    fun showLine(iccid: String): String = configLine("show", iccid)

    fun applyLine(iccid: String): String = configLine("apply", iccid)

    fun createLine(subscriptionId: Int, slot: Int): String {
        require(subscriptionId >= 0)
        require(slot == 0 || slot == 1)
        return "$SCRIPT config create $subscriptionId $slot"
    }

    fun refreshIdentityLine(iccid: String, subscriptionId: Int, slot: Int): String {
        require(digitsOnly(iccid))
        require(subscriptionId >= 0)
        require(slot == 0 || slot == 1)
        return "$SCRIPT config refresh-identity $iccid $subscriptionId $slot"
    }

    fun handle(index: Int): String = "config-$index"

    fun digitsOnly(iccid: String): Boolean = iccid.isNotEmpty() && iccid.all(Char::isDigit)

    private fun configLine(action: String, iccid: String): String {
        require(digitsOnly(iccid))
        return "$SCRIPT config $action $iccid"
    }
}
