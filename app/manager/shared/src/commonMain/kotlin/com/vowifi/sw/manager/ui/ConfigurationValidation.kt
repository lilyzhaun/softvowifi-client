package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConfigurationCodec
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationOverrides

internal enum class ConfigurationFieldKey { Smsc, Epdg, Realm, Dns, SipSecurity, PaniInitial, PaniCountry, DigestRealm }
internal enum class ConfigurationEditStatus { Idle, Invalid, Unchanged, Saving, Saved, Failed, Unconfirmed, BusyRejected, Unavailable }

internal fun configurationFieldErrors(values: ConfigurationOverrides): Map<ConfigurationFieldKey, String> {
    val blank = values.copy(smsc = "", epdgFqdn = "", imsRealm = "", dns = "", imsSipSecurity = null, paniInitial = null, paniCountry = null, imsDigestRealm = null)
    val fields = mapOf(
        ConfigurationFieldKey.Smsc to blank.copy(smsc = values.smsc),
        ConfigurationFieldKey.Epdg to blank.copy(epdgFqdn = values.epdgFqdn),
        ConfigurationFieldKey.Realm to blank.copy(imsRealm = values.imsRealm),
        ConfigurationFieldKey.Dns to blank.copy(dns = values.dns),
        ConfigurationFieldKey.SipSecurity to blank.copy(imsSipSecurity = values.imsSipSecurity),
        ConfigurationFieldKey.PaniInitial to blank.copy(paniInitial = values.paniInitial),
        ConfigurationFieldKey.PaniCountry to blank.copy(paniCountry = values.paniCountry),
        ConfigurationFieldKey.DigestRealm to blank.copy(imsDigestRealm = values.imsDigestRealm),
    )
    return buildMap {
        fields.forEach { (field, input) ->
            try {
                ConfigurationCodec.encode(input)
            } catch (_: ConfigurationFormatException.InvalidSmsc) {
                put(field, "SMSC 须以 + 开头，或留空")
            } catch (_: ConfigurationFormatException.IdentityLeak) {
                put(field, "请勿填写完整 SIM 身份标识")
            } catch (_: ConfigurationFormatException.InvalidSipSecurity) {
                put(field, "请选择受支持的显式安全策略")
            } catch (_: ConfigurationFormatException.InvalidPaniInitial) {
                put(field, "请选择受支持的初始 PANI 策略")
            } catch (_: ConfigurationFormatException.InvalidPaniCountry) {
                put(field, "输入两个 ASCII 字母，或留空继承归属国家")
            } catch (_: ConfigurationFormatException.InvalidDigestRealm) {
                put(field, "输入认证域，不含用户信息、端口或控制字符，或留空继承")
            }
        }
    }
}
