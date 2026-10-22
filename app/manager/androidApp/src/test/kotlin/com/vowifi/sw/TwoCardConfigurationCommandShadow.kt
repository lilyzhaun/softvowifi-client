@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidSuCommand
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.data.ConfigurationControlCommand
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

// Read-only engine receipts for two stopped cards; the target has no DNS override (live network DNS).
@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class TwoCardConfigurationCommandShadow {
    @Implementation
    fun run(command: String): AndroidSuResult = success(when (command) {
        ConfigurationControlCommand.listLine() ->
            """[{"ok":true,"iccid":"$TARGET","state":"stopped"},{"ok":true,"iccid":"$OTHER","state":"stopped"}]"""
        ConfigurationControlCommand.showLine(TARGET) -> shown("{}")
        ConfigurationControlCommand.showLine(OTHER) -> shown("""{"sim2":"203.0.113.8:53"}""")
        else -> error("Unexpected command")
    })

    private fun success(output: String): AndroidSuResult = AndroidSuResult(false, 0, output, false)

    private fun shown(overrides: String) = """{"ok":true,"displayLabel":"0001","lifecycle":"stopped","transport":"sim2","smsc":"+15550000001","epdgFqdn":"","imsRealm":"ims.mnc001.mcc001.3gppnetwork.org","dns":"","dnsOverrides":$overrides,"resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"home.card.test","mcc":"001","mnc":"01"}"""
}

private const val TARGET = "0000000000000000001"
private const val OTHER = "0000000000000000002"
