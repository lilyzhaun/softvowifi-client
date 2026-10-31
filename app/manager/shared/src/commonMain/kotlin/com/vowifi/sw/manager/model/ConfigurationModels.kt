package com.vowifi.sw.manager.model

import com.vowifi.sw.manager.data.SimOwnerId

data class ConfigurationInstanceId(val value: String)

enum class ConfigurationIdentitySource(val wireValue: String) {
    LEGACY("legacy"),
    OVERRIDE("override"),
    FORMULA("formula"),
    ISIM("isim"),
    UNKNOWN("unknown"),
}

enum class ConfigurationLifecycle {
    CONFIG_MISSING,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
}

enum class ConfigurationFormatState {
    CURRENT,
    LEGACY,
    UNSUPPORTED,
}

data class ConfigurationOverrides(
    val transport: ConfigurationTransport,
    val smsc: String,
    val epdgFqdn: String,
    val imsRealm: String,
    val dns: String,
    val imsSipSecurity: String? = null,
    val paniInitial: String? = null,
    val paniCountry: String? = null,
    val imsDigestRealm: String? = null,
    // Per-transport DNS overrides; [dns] replaces the entry for [transport] when encoded.
    val dnsOverrides: Map<String, String> = emptyMap(),
    val expectedRevision: String? = null,
) {
    override fun toString(): String = "ConfigurationOverrides"
}

data class ConfigurationImsPolicy(
    val security: String = "tcp_ipsec_3gpp",
    val securitySource: String = "default",
    val initial: String = "",
    val initialSource: String = "default",
    val country: String = "",
    val countrySource: String = "home_mcc",
    val digestRealm: String = "",
    val digestRealmSource: String = "identity",
) {
    val securityOverride: String get() = security.takeIf { securitySource == "user" }.orEmpty()
    val initialOverride: String get() = initial.takeIf { initialSource == "user" }.orEmpty()
    val countryOverride: String get() = country.takeIf { countrySource == "user" }.orEmpty()
    val digestRealmOverride: String get() = digestRealm.takeIf { digestRealmSource == "user" }.orEmpty()
}

data class ConfigurationSnapshot(
    val displayLabel: String,
    val lifecycle: ConfigurationLifecycle,
    val transport: ConfigurationTransport,
    val smsc: String,
    val epdgFqdn: String,
    val imsRealm: String,
    val dns: String,
    val resolvedEpdgFqdn: String,
    val resolvedImsRealm: String,
    val mcc: String,
    val mnc: String,
    val imsPolicy: ConfigurationImsPolicy = ConfigurationImsPolicy(),
    val identitySource: ConfigurationIdentitySource = ConfigurationIdentitySource.UNKNOWN,
    val dnsOverrides: Map<String, String> = emptyMap(),
    val formatState: ConfigurationFormatState = ConfigurationFormatState.CURRENT,
    val configVersion: Int? = null,
    val configRevision: String? = null,
    val facts: ConfigurationFacts? = null,
    val simAttributeReadStatus: SIMAttributeReadStatus? = null,
) {
    override fun toString(): String = "ConfigurationSnapshot"
}

sealed interface SubscriptionDiscovery {
    data object NotAttempted : SubscriptionDiscovery
    data object Missing : SubscriptionDiscovery
    data object Unavailable : SubscriptionDiscovery
    data class Matched(
        val carrierLabel: String,
        val slotIndex: Int?,
        val mcc: String?,
        val mnc: String?,
    ) : SubscriptionDiscovery
}

data class ConfigurationInstance(
    val instanceId: ConfigurationInstanceId,
    val displayLabel: String,
    val lifecycle: ConfigurationLifecycle,
    val transport: ConfigurationTransport,
    val smsc: String,
    val epdgFqdn: String,
    val imsRealm: String,
    val dns: String,
    val resolvedEpdgFqdn: String,
    val resolvedImsRealm: String,
    val mcc: String,
    val mnc: String,
    val systemSubscription: SubscriptionDiscovery = SubscriptionDiscovery.NotAttempted,
    val draftOwnerId: SimOwnerId? = null,
    val imsPolicy: ConfigurationImsPolicy = ConfigurationImsPolicy(),
    val identitySource: ConfigurationIdentitySource = ConfigurationIdentitySource.UNKNOWN,
    // Saved DNS override per transport; [dns] is the entry for [transport], empty means live network DNS.
    val dnsOverrides: Map<String, String> = emptyMap(),
    // The running instance's SIM-derived SMSC, separate from the editable disk value.
    val resolvedSmsc: String = "",
    val formatState: ConfigurationFormatState = ConfigurationFormatState.CURRENT,
    val configVersion: Int? = null,
    val configRevision: String? = null,
    val facts: ConfigurationFacts? = null,
    val runtimeFacts: ConfigurationRuntimeFacts? = null,
    val simAttributeReadStatus: SIMAttributeReadStatus? = null,
) {
    override fun toString(): String = "ConfigurationInstance"
}

sealed interface ConfigurationUiState {
    data object Loading : ConfigurationUiState

    data class Ready(
        val instances: List<ConfigurationInstance>,
        val applyingInstanceId: ConfigurationInstanceId? = null,
        val message: String? = null,
        val isFixture: Boolean = false,
        val networkOutlets: ConfigurationNetworkOutlets = ConfigurationNetworkOutlets(),
    ) : ConfigurationUiState

    data object Empty : ConfigurationUiState

    data class Failure(
        val message: String,
    ) : ConfigurationUiState

    data class PermissionDenied(
        val message: String,
    ) : ConfigurationUiState

    data class Unavailable(
        val message: String,
    ) : ConfigurationUiState
}

sealed class ConfigurationFormatException(
    message: String,
) : IllegalArgumentException(message) {
    class Unparseable : ConfigurationFormatException("配置结果无法解析")
    class Unsuccessful : ConfigurationFormatException("配置结果未成功")
    class PayloadTooLarge : ConfigurationFormatException("配置补丁超过 64 KiB 限制")
    class ForbiddenField : ConfigurationFormatException("配置包含禁止字段")
    class IdentityLeak : ConfigurationFormatException("配置包含敏感标识")
    class InvalidTransport : ConfigurationFormatException("底层网络值无效")
    class InvalidSmsc : ConfigurationFormatException("SMSC 格式无效")
    class InvalidLifecycle : ConfigurationFormatException("生命周期无效")
    class InvalidLabel : ConfigurationFormatException("显示标签无效")
    class InvalidSipSecurity : ConfigurationFormatException("IMS SIP 安全策略无效")
    class InvalidPaniInitial : ConfigurationFormatException("初始 PANI 策略无效")
    class InvalidPaniCountry : ConfigurationFormatException("国家参数须为两个 ASCII 字母")
    class InvalidDigestRealm : ConfigurationFormatException("Digest 认证域格式无效")
    class InvalidDns : ConfigurationFormatException("DNS 须为 IP 或 IP:端口")
}
