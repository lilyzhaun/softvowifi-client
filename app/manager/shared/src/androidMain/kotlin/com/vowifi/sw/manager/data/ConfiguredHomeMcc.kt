package com.vowifi.sw.manager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the home MCC from the instance's own module configuration through the public control script. */
internal suspend fun configuredHomeMcc(iccid: String): String? = withContext(Dispatchers.IO) {
    if (!ConfigurationControlCommand.digitsOnly(iccid)) return@withContext null
    runCatching {
        val result = AndroidSuCommand().run(ConfigurationControlCommand.showLine(iccid))
        if (result.timedOut || result.exitCode != 0) null else ConfigurationCodec.show(result.output).mcc
    }.getOrNull()
}
