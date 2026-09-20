package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ConfigurationEvaluatorTest {
    @Test
    fun mapsEveryUiStateVariant() {
        assertIs<ConfigurationUiState.Loading>(ConfigurationUiState.Loading)
        assertIs<ConfigurationUiState.Empty>(ConfigurationEvaluator.ready(emptyList()))
        assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.fromShow(redactedShowJson(), ConfigurationInstanceId(FAKE_INSTANCE_ID)),
        )
        assertIs<ConfigurationUiState.Failure>(ConfigurationEvaluator.failure())
        assertIs<ConfigurationUiState.PermissionDenied>(ConfigurationEvaluator.permissionDenied())
        assertIs<ConfigurationUiState.Unavailable>(ConfigurationEvaluator.unavailable())
    }

    @Test
    fun bindsOpaqueInstanceIdWithoutSubscriberFields() {
        val ready = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.fromShow(redactedShowJson(), ConfigurationInstanceId(FAKE_INSTANCE_ID)),
        )

        val instance = ready.instances.single()
        assertEquals(FAKE_INSTANCE_ID, instance.instanceId.value)
        assertEquals(FAKE_DISPLAY_LABEL, instance.displayLabel)
        assertEquals(ConfigurationLifecycle.STOPPED, instance.lifecycle)
        assertEquals(ConfigurationTransport.WIFI, instance.transport)
        assertEquals(FAKE_MCC, instance.mcc)
        assertEquals(FAKE_MNC, instance.mnc)
        assertFalse(ready.isFixture)
        assertNull(ready.applyingInstanceId)
        assertNull(ready.message)
        assertPrivate(instance.displayLabel)
        assertPrivate(instance.instanceId.value)
        assertPrivate(instance.resolvedEpdgFqdn)
    }

    @Test
    fun keepsMultipleInstancesAndFixtureProvenance() {
        val first = ConfigurationEvaluator.instance(
            ConfigurationInstanceId("c1"),
            ConfigurationCodec.show(redactedShowJson(displayLabel = "实例 •••• 1234")),
        )
        val second = ConfigurationEvaluator.instance(
            ConfigurationInstanceId("c2"),
            ConfigurationCodec.show(redactedShowJson(displayLabel = "实例 •••• 5678", transport = "sim1")),
        )

        val ready = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.ready(
                instances = listOf(first, second),
                applyingInstanceId = ConfigurationInstanceId("c2"),
                message = "正在应用配置",
                isFixture = true,
            ),
        )

        assertEquals(listOf("c1", "c2"), ready.instances.map { it.instanceId.value })
        assertEquals(ConfigurationTransport.SIM1, ready.instances[1].transport)
        assertEquals("c2", ready.applyingInstanceId?.value)
        assertEquals("正在应用配置", ready.message)
        assertTrue(ready.isFixture)
    }

    @Test
    fun mapsShowFailuresToBoundedUiErrors() {
        val forbidden = assertIs<ConfigurationUiState.Failure>(
            ConfigurationEvaluator.fromShow(
                redactedShowJson(extra = buildJsonObject { put("imsi", "x") }),
                ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ),
        )
        val leaked = assertIs<ConfigurationUiState.Failure>(
            ConfigurationEvaluator.fromShow(
                redactedShowJson(dns = FAKE_DIGIT_RUN),
                ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ),
        )
        val malformed = assertIs<ConfigurationUiState.Failure>(
            ConfigurationEvaluator.fromShow(
                """{"ok":true,"displayLabel":""",
                ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ),
        )
        val unsuccessful = assertIs<ConfigurationUiState.Failure>(
            ConfigurationEvaluator.fromShow(
                redactedShowJson(ok = false),
                ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ),
        )

        assertEquals("配置包含禁止字段", forbidden.message)
        assertEquals("配置包含敏感标识", leaked.message)
        assertEquals("配置结果无法解析", malformed.message)
        assertEquals("配置结果未成功", unsuccessful.message)
        assertPrivate(forbidden.message)
        assertPrivate(leaked.message)
        assertPrivate(malformed.message)
        assertFalse(malformed.message.contains("Unexpected", ignoreCase = true))
    }

    @Test
    fun dropsUnsafeReadyCopyAndCommandFailureMessages() {
        val snapshot = ConfigurationCodec.show(redactedShowJson())
        val instance = ConfigurationEvaluator.instance(ConfigurationInstanceId(FAKE_INSTANCE_ID), snapshot)
        val ready = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.ready(
                instances = listOf(instance),
                message = "Unexpected JSON token at iccid=$FAKE_DIGIT_RUN",
            ),
        )
        val unavailable = assertIs<ConfigurationUiState.Unavailable>(
            ConfigurationEvaluator.fromShow(
                """{"ok":false,"code":"USAGE_INVALID","message":"command arguments are invalid"}""",
                ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ),
        )
        val failure = assertIs<ConfigurationUiState.Failure>(
            ConfigurationEvaluator.fromShow(
                """{"ok":false,"code":"CONFIG_MISSING","message":"/data/adb/vowifi/config/x.json"}""",
                ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ),
        )
        val rawFailure = ConfigurationEvaluator.failure("kotlinx.serialization.MissingFieldException")

        assertNull(ready.message)
        assertEquals("配置命令不可用", unavailable.message)
        assertEquals("无法读取配置", failure.message)
        assertEquals("无法读取配置", rawFailure.message)
        assertPrivate(unavailable.message)
        assertPrivate(failure.message)
        assertPrivate(rawFailure.message)
    }

    @Test
    fun readyAcceptsFifteenDigitSmscButRejectsOtherIdentityRuns() {
        val accepted = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.fromShow(
                redactedShowJson(smsc = FAKE_E164_SMSC),
                ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ),
        )
        val leakedId = ConfigurationEvaluator.ready(
            listOf(
                ConfigurationEvaluator.instance(
                    ConfigurationInstanceId(FAKE_DIGIT_RUN),
                    ConfigurationCodec.show(redactedShowJson()),
                ),
            ),
        )
        val leakedMessage = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.ready(
                instances = listOf(
                    ConfigurationEvaluator.instance(
                        ConfigurationInstanceId(FAKE_INSTANCE_ID),
                        ConfigurationCodec.show(redactedShowJson()),
                    ),
                ),
                message = FAKE_DIGIT_RUN,
            ),
        )

        assertEquals(FAKE_E164_SMSC, accepted.instances.single().smsc)
        assertIs<ConfigurationUiState.Failure>(leakedId)
        assertNull(leakedMessage.message)
    }

    @Test
    fun admitsSavedMessageAfterApplyAndDropsArbitraryText() {
        val instance = ConfigurationEvaluator.instance(
            ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ConfigurationCodec.show(redactedShowJson()),
        )
        val applying = ConfigurationEvaluator.ready(
            instances = listOf(instance),
            applyingInstanceId = ConfigurationInstanceId(FAKE_INSTANCE_ID),
        )
        val saved = assertIs<ConfigurationUiState.Ready>(ConfigurationEvaluator.readyAfterApply(applying))
        val raw = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.ready(
                instances = listOf(instance),
                message = "saved ok iccid=$FAKE_DIGIT_RUN",
            ),
        )
        val failedRefresh = ConfigurationEvaluator.readyAfterApply(ConfigurationEvaluator.failure())

        val message = checkNotNull(saved.message)
        assertEquals("配置已保存", message)
        assertNull(saved.applyingInstanceId)
        assertEquals(instance.lifecycle, saved.instances.single().lifecycle)
        assertNull(raw.message)
        assertIs<ConfigurationUiState.Failure>(failedRefresh)
        assertPrivate(message)
    }

    @Test
    fun admitsCreatedMessageAfterProvisionAndDropsRawCreateOutput() {
        val instance = ConfigurationEvaluator.instance(
            ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ConfigurationCodec.show(redactedShowJson()),
        )
        val created = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.readyAfterCreate(
                ConfigurationEvaluator.ready(listOf(instance), applyingInstanceId = ConfigurationInstanceId(FAKE_INSTANCE_ID)),
            ),
        )
        val unsupported = ConfigurationEvaluator.failure("当前 SIM 不支持创建配置")
        val failed = ConfigurationEvaluator.failure("无法创建配置")
        val unreadable = ConfigurationEvaluator.failure("无法读取 SIM 身份")
        val raw = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.ready(
                instances = listOf(instance),
                message = "CREATED ok iccid=$FAKE_DIGIT_RUN",
            ),
        )

        val message = checkNotNull(created.message)
        assertEquals("配置已创建", message)
        assertNull(created.applyingInstanceId)
        assertEquals("当前 SIM 不支持创建配置", unsupported.message)
        assertEquals("无法创建配置", failed.message)
        assertEquals("无法读取 SIM 身份", unreadable.message)
        assertNull(raw.message)
        assertPrivate(message)
        assertPrivate(unsupported.message)
        assertPrivate(failed.message)
        assertPrivate(unreadable.message)
    }

    @Test
    fun admitsAutoProvisionMessagesAndPreservesReadyOnFailure() {
        val instance = ConfigurationEvaluator.instance(
            ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ConfigurationCodec.show(redactedShowJson()),
        )
        val listing = ConfigurationEvaluator.ready(listOf(instance))
        val creating = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.ready(listOf(instance), message = "正在自动创建配置"),
        )
        val created = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.readyAfterAutoCreate(listing),
        )
        val preserved = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.preserveAfterAutoFailure(
                listing,
                ConfigurationEvaluator.failure("无法创建配置"),
            ),
        )
        val identity = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.preserveAfterAutoFailure(
                listing,
                ConfigurationEvaluator.failure("无法读取 SIM 身份"),
            ),
        )
        val emptyPermission = assertIs<ConfigurationUiState.PermissionDenied>(
            ConfigurationEvaluator.preserveAfterAutoFailure(
                ConfigurationUiState.Empty,
                ConfigurationEvaluator.permissionDenied(),
            ),
        )
        val emptyFailed = assertIs<ConfigurationUiState.Failure>(
            ConfigurationEvaluator.preserveAfterAutoFailure(
                ConfigurationUiState.Empty,
                ConfigurationEvaluator.failure("无法创建配置"),
            ),
        )
        val raw = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.ready(
                instances = listOf(instance),
                message = "auto-created iccid=$FAKE_DIGIT_RUN",
            ),
        )

        assertEquals("正在自动创建配置", creating.message)
        assertEquals("配置已自动创建", created.message)
        assertEquals("无法自动创建配置", preserved.message)
        assertEquals("无法读取 SIM 身份", identity.message)
        assertEquals("请在 KernelSU 中授予 SoftVoWiFi root 权限", emptyPermission.message)
        assertEquals("无法自动创建配置", emptyFailed.message)
        assertTrue(ConfigurationEvaluator.isExistingTarget(ConfigurationEvaluator.failure("配置已存在")))
        assertFalse(ConfigurationEvaluator.isExistingTarget(ConfigurationEvaluator.failure("无法创建配置")))
        assertNull(raw.message)
        assertPrivate(checkNotNull(created.message))
        assertPrivate(checkNotNull(preserved.message))
    }

    @Test
    fun admitsNetworkDnsUnavailableMessageOnReadyAndEmpty() {
        val instance = ConfigurationEvaluator.instance(
            ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ConfigurationCodec.show(redactedShowJson()),
        )
        val listing = ConfigurationEvaluator.ready(listOf(instance))
        val denied = ConfigurationEvaluator.failure("底层网络 DNS 不可用")
        val preserved = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.preserveAfterAutoFailure(listing, denied),
        )
        val empty = assertIs<ConfigurationUiState.Failure>(
            ConfigurationEvaluator.preserveAfterAutoFailure(ConfigurationUiState.Empty, denied),
        )

        assertEquals("底层网络 DNS 不可用", denied.message)
        assertEquals("底层网络 DNS 不可用", preserved.message)
        assertEquals("底层网络 DNS 不可用", empty.message)
        assertPrivate(denied.message)
        assertPrivate(checkNotNull(preserved.message))
        assertPrivate(empty.message)
    }
}
