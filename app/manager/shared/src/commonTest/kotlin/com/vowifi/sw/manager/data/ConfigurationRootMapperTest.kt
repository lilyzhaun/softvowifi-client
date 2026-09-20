package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class ConfigurationRootMapperTest {
    @Test
    fun mapsPermissionUnavailableTimeoutAndFailureWithoutRawText() {
        val permission = assertIs<ConfigurationUiState.PermissionDenied>(
            ConfigurationRootMapper.map(outcome(exitCode = 1, output = "su: error=13 Permission denied")),
        )
        val missing = assertIs<ConfigurationUiState.Unavailable>(
            ConfigurationRootMapper.map(outcome(exitCode = 127, output = "No such file or directory")),
        )
        val usage = assertIs<ConfigurationUiState.Unavailable>(
            ConfigurationRootMapper.map(
                outcome(
                    exitCode = 64,
                    output = """{"ok":false,"code":"USAGE","message":"control.sh config show <iccid>"}""",
                ),
            ),
        )
        val timeout = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.map(outcome(timedOut = true, exitCode = -1, output = "su command timed out")),
        )
        val overflow = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.map(
                outcome(overflowed = true, output = """{"ok":true,"iccid":"8986000000000000001"}"""),
            ),
        )
        val writeFailed = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.map(outcome(writeFailed = true, exitCode = -1, output = """{"transport":"wifi"}""")),
        )
        val missingConfig = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.map(
                outcome(
                    exitCode = 3,
                    output = """{"ok":false,"code":"CONFIG_MISSING","message":"/data/adb/vowifi/config/x.json"}""",
                ),
            ),
        )

        assertEquals("请在 KernelSU 中授予 SoftVoWiFi root 权限", permission.message)
        assertEquals("配置命令不可用", missing.message)
        assertEquals("配置命令不可用", usage.message)
        assertEquals("无法读取配置", timeout.message)
        assertEquals("无法读取配置", overflow.message)
        assertEquals("无法读取配置", writeFailed.message)
        assertEquals("无法读取配置", missingConfig.message)
        assertPrivate(permission.message)
        assertPrivate(usage.message)
        assertPrivate(missingConfig.message)
        assertNull(ConfigurationRootMapper.map(outcome(output = """[{"ok":true}]""")))
    }

    @Test
    fun mapsCreateRootCodesToBoundedMessagesWithoutRawOutput() {
        val created = ConfigurationRootMapper.mapCreate(
            outcome(output = """{"ok":true,"code":"CREATED","message":"config created"}"""),
        )
        val exists = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.mapCreate(
                outcome(
                    exitCode = 1,
                    output = """{"ok":false,"code":"TARGET_EXISTS","message":"/data/adb/vowifi/config/8986000000000000001.json"}""",
                ),
            ),
        )
        val mismatch = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.mapCreate(
                outcome(
                    exitCode = 17,
                    output = """{"ok":false,"code":"IDENTITY_MISMATCH","message":"SIM identity does not match config"}""",
                ),
            ),
        )
        val unreadable = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.mapCreate(
                outcome(
                    exitCode = 18,
                    output = """{"ok":false,"code":"IDENTITY_UNREADABLE","message":"SIM identity could not be read"}""",
                ),
            ),
        )
        val unsupported = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.mapCreate(
                outcome(
                    exitCode = 1,
                    output = """{"ok":false,"code":"PROFILE_UNSUPPORTED","message":"profile is unsupported"}""",
                ),
            ),
        )
        val invalid = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.mapCreate(
                outcome(
                    exitCode = 1,
                    output = """{"ok":false,"code":"CANDIDATE_INVALID","message":"candidate is invalid"}""",
                ),
            ),
        )
        val permission = assertIs<ConfigurationUiState.PermissionDenied>(
            ConfigurationRootMapper.mapCreate(
                outcome(exitCode = 1, output = "su: error=13 Permission denied"),
            ),
        )
        val timeout = assertIs<ConfigurationUiState.Failure>(
            ConfigurationRootMapper.mapCreate(outcome(timedOut = true, exitCode = -1, output = "su command timed out")),
        )

        assertNull(created)
        assertEquals("配置已存在", exists.message)
        assertEquals("SIM 与配置身份不一致", mismatch.message)
        assertEquals("无法读取 SIM 身份", unreadable.message)
        assertEquals("当前 SIM 不支持创建配置", unsupported.message)
        assertEquals("无法创建配置", invalid.message)
        assertEquals("请在 KernelSU 中授予 SoftVoWiFi root 权限", permission.message)
        assertEquals("无法创建配置", timeout.message)
        assertPrivate(exists.message)
        assertPrivate(mismatch.message)
        assertPrivate(unreadable.message)
        assertFalse(exists.message.contains("8986"))
        assertFalse(mismatch.message.contains("identity", ignoreCase = true))
    }

    @Test
    fun writeFailedPermissionDeniedStaysPermissionDenied() {
        val denied = assertIs<ConfigurationUiState.PermissionDenied>(
            ConfigurationRootMapper.map(
                outcome(
                    writeFailed = true,
                    exitCode = -1,
                    output = "su: error=13 Permission denied",
                ),
            ),
        )

        assertEquals("请在 KernelSU 中授予 SoftVoWiFi root 权限", denied.message)
        assertPrivate(denied.message)
    }
}

private fun outcome(
    timedOut: Boolean = false,
    overflowed: Boolean = false,
    writeFailed: Boolean = false,
    exitCode: Int = 0,
    output: String = "",
) = ConfigurationCommandOutcome(timedOut, overflowed, writeFailed, exitCode, output)
