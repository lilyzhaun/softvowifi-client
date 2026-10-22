package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import java.io.IOException
import kotlinx.coroutines.runInterruptible

internal const val NETWORK_OUTLETS_COMMAND = "/data/adb/modules/vowifi_sw/bin/vowifi-engine-arm64 network-list"

internal suspend fun readAndroidNetworkOutlets(): ConfigurationNetworkOutlets = try {
    val result = runInterruptible { AndroidSuCommand(timeoutSeconds = 3, maxBytes = 32768).run(NETWORK_OUTLETS_COMMAND) }
    if (result.timedOut || result.overflowed || result.writeFailed || result.exitCode != 0) NetworkOutletsCodec.failure()
    else NetworkOutletsCodec.decode(result.output)
} catch (_: IOException) {
    NetworkOutletsCodec.failure()
}
