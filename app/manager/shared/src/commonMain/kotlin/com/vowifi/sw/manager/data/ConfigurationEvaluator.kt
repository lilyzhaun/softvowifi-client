package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationSnapshot
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SubscriptionDiscovery

object ConfigurationEvaluator {
    fun permissionDenied(
        message: String = PERMISSION,
    ): ConfigurationUiState.PermissionDenied =
        ConfigurationUiState.PermissionDenied(bounded(message, PERMISSION))

    fun unavailable(
        message: String = UNAVAILABLE,
    ): ConfigurationUiState.Unavailable =
        ConfigurationUiState.Unavailable(bounded(message, UNAVAILABLE))

    fun failure(
        message: String = UNREADABLE,
    ): ConfigurationUiState.Failure =
        ConfigurationUiState.Failure(bounded(message, UNREADABLE))

    fun fromShow(
        output: String,
        instanceId: ConfigurationInstanceId,
    ): ConfigurationUiState {
        val commandFailure = ConfigurationCodec.failure(output)
        if (commandFailure != null) {
            return when (commandFailure.code) {
                "USAGE", "USAGE_INVALID" -> unavailable()
                else -> failure()
            }
        }
        return try {
            ready(listOf(instance(instanceId, ConfigurationCodec.show(output))))
        } catch (error: ConfigurationFormatException) {
            failure(error.message ?: UNPARSEABLE)
        }
    }

    fun ready(
        instances: List<ConfigurationInstance>,
        applyingInstanceId: ConfigurationInstanceId? = null,
        message: String? = null,
        isFixture: Boolean = false,
    ): ConfigurationUiState {
        if (instances.isEmpty()) return ConfigurationUiState.Empty
        if (instances.any(::leaksIdentity)) return failure(IDENTITY)
        return ConfigurationUiState.Ready(
            instances = instances,
            applyingInstanceId = applyingInstanceId,
            message = message?.let { boundedOrNull(it) },
            isFixture = isFixture,
        )
    }

    fun readyAfterApply(refreshed: ConfigurationUiState): ConfigurationUiState =
        readyAfterMessage(refreshed, SAVED)

    fun readyAfterCreate(refreshed: ConfigurationUiState): ConfigurationUiState =
        readyAfterMessage(refreshed, CREATED)

    fun readyAfterAutoCreate(refreshed: ConfigurationUiState): ConfigurationUiState =
        readyAfterMessage(refreshed, AUTO_CREATED)

    fun preserveAfterAutoFailure(
        listing: ConfigurationUiState,
        failure: ConfigurationUiState,
    ): ConfigurationUiState = when (listing) {
        is ConfigurationUiState.Ready -> ready(
            instances = listing.instances,
            message = autoFailureMessage(failure),
            isFixture = listing.isFixture,
        )
        ConfigurationUiState.Empty -> emptyAutoFailure(failure)
        ConfigurationUiState.Loading,
        is ConfigurationUiState.Failure,
        is ConfigurationUiState.PermissionDenied,
        is ConfigurationUiState.Unavailable,
        -> listing
    }

    fun isExistingTarget(state: ConfigurationUiState): Boolean =
        state is ConfigurationUiState.Failure && state.message == TARGET_EXISTS

    private fun readyAfterMessage(
        refreshed: ConfigurationUiState,
        message: String,
    ): ConfigurationUiState =
        when (refreshed) {
            is ConfigurationUiState.Ready -> ready(
                instances = refreshed.instances,
                message = message,
                isFixture = refreshed.isFixture,
            )
            ConfigurationUiState.Loading,
            ConfigurationUiState.Empty,
            is ConfigurationUiState.Failure,
            is ConfigurationUiState.PermissionDenied,
            is ConfigurationUiState.Unavailable,
            -> refreshed
        }

    fun instance(
        id: ConfigurationInstanceId,
        snapshot: ConfigurationSnapshot,
        systemSubscription: SubscriptionDiscovery = SubscriptionDiscovery.NotAttempted,
    ): ConfigurationInstance = ConfigurationInstance(
        instanceId = id,
        displayLabel = snapshot.displayLabel,
        lifecycle = snapshot.lifecycle,
        transport = snapshot.transport,
        smsc = snapshot.smsc,
        epdgFqdn = snapshot.epdgFqdn,
        imsRealm = snapshot.imsRealm,
        dns = snapshot.dns,
        resolvedEpdgFqdn = snapshot.resolvedEpdgFqdn,
        resolvedImsRealm = snapshot.resolvedImsRealm,
        mcc = snapshot.mcc,
        mnc = snapshot.mnc,
        systemSubscription = systemSubscription,
        imsPolicy = snapshot.imsPolicy,
        identitySource = snapshot.identitySource,
    )
}

private fun leaksIdentity(instance: ConfigurationInstance): Boolean {
    val snapshotFields = listOf(
        instance.instanceId.value,
        instance.displayLabel,
        instance.epdgFqdn,
        instance.imsRealm,
        instance.dns,
        instance.resolvedEpdgFqdn,
        instance.resolvedImsRealm,
        instance.mcc,
        instance.mnc,
    )
    val discoveryFields = when (val discovery = instance.systemSubscription) {
        is SubscriptionDiscovery.Matched -> listOfNotNull(
            discovery.carrierLabel,
            discovery.mcc,
            discovery.mnc,
        )
        SubscriptionDiscovery.NotAttempted,
        SubscriptionDiscovery.Missing,
        SubscriptionDiscovery.Unavailable,
        -> emptyList()
    }
    return (snapshotFields + discoveryFields).any(::containsIdentityDigits)
}

private fun bounded(message: String, fallback: String): String =
    message.takeIf(::isBoundedMessage) ?: fallback

private fun boundedOrNull(message: String): String? =
    message.takeIf(::isBoundedMessage)

private fun isBoundedMessage(message: String): Boolean =
    message in ALLOWED_MESSAGES && !containsIdentityDigits(message)

private fun autoFailureMessage(failure: ConfigurationUiState): String = when (failure) {
    is ConfigurationUiState.Failure -> when (failure.message) {
        CREATE_FAILED -> AUTO_CREATE_FAILED
        else -> failure.message
    }
    is ConfigurationUiState.PermissionDenied -> failure.message
    is ConfigurationUiState.Unavailable -> failure.message
    ConfigurationUiState.Empty,
    ConfigurationUiState.Loading,
    is ConfigurationUiState.Ready,
    -> AUTO_CREATE_FAILED
}

private fun emptyAutoFailure(failure: ConfigurationUiState): ConfigurationUiState = when (failure) {
    is ConfigurationUiState.PermissionDenied -> failure
    is ConfigurationUiState.Unavailable -> failure
    is ConfigurationUiState.Failure -> when (failure.message) {
        CREATE_FAILED -> ConfigurationEvaluator.failure(AUTO_CREATE_FAILED)
        else -> failure
    }
    ConfigurationUiState.Empty,
    ConfigurationUiState.Loading,
    is ConfigurationUiState.Ready,
    -> ConfigurationEvaluator.failure(AUTO_CREATE_FAILED)
}

private const val UNPARSEABLE = "配置结果无法解析"
private const val UNSUCCESSFUL = "配置结果未成功"
private const val FORBIDDEN = "配置包含禁止字段"
private const val IDENTITY = "配置包含敏感标识"
private const val INVALID_TRANSPORT = "底层网络值无效"
private const val INVALID_SMSC = "SMSC 格式无效"
private const val INVALID_LIFECYCLE = "生命周期无效"
private const val INVALID_LABEL = "显示标签无效"
private const val UNREADABLE = "无法读取配置"
private const val PERMISSION = "请在 KernelSU 中授予 SoftVoWiFi root 权限"
private const val UNAVAILABLE = "配置命令不可用"
private const val APPLYING = "正在应用配置"
private const val SAVED = "配置已保存"
private const val CREATING = "正在创建配置"
private const val CREATED = "配置已创建"
private const val UNSUPPORTED_CREATE = "当前 SIM 不支持创建配置"
private const val CREATE_FAILED = "无法创建配置"
private const val IDENTITY_UNREADABLE = "无法读取 SIM 身份"
private const val TARGET_EXISTS = "配置已存在"
private const val IDENTITY_MISMATCH = "SIM 与配置身份不一致"
private const val AUTO_CREATING = "正在自动创建配置"
private const val AUTO_CREATED = "配置已自动创建"
private const val AUTO_CREATE_FAILED = "无法自动创建配置"
private const val DNS_UNAVAILABLE = "底层网络 DNS 不可用"
private const val IDENTITY_REFRESH_FAILED = "无法刷新 SIM 身份"
private const val IDENTITY_REFRESH_UNCONFIRMED = "身份刷新未确认，请重新读取配置"
private const val IDENTITY_REFRESH_RUNNING = "请先停止该 SIM 的引擎，再刷新身份"
private const val IDENTITY_REFRESH_MISMATCH = "当前 SIM 身份未通过校验"

private val ALLOWED_MESSAGES = setOf(
    IDENTITY_REFRESH_FAILED,
    IDENTITY_REFRESH_UNCONFIRMED,
    IDENTITY_REFRESH_RUNNING,
    IDENTITY_REFRESH_MISMATCH,
    UNPARSEABLE,
    UNSUCCESSFUL,
    FORBIDDEN,
    IDENTITY,
    INVALID_TRANSPORT,
    INVALID_SMSC,
    INVALID_LIFECYCLE,
    INVALID_LABEL,
    UNREADABLE,
    PERMISSION,
    UNAVAILABLE,
    APPLYING,
    SAVED,
    CREATING,
    CREATED,
    UNSUPPORTED_CREATE,
    CREATE_FAILED,
    IDENTITY_UNREADABLE,
    TARGET_EXISTS,
    IDENTITY_MISMATCH,
    AUTO_CREATING,
    AUTO_CREATED,
    AUTO_CREATE_FAILED,
    DNS_UNAVAILABLE,
)
