package com.vowifi.sw.manager.data

internal val HEALTHY_WITH_SECRETS = """
    {
      "ok": true,
      "module": {
        "installed": true,
        "id": "vowifi_sw",
        "version": "0.3.9",
        "versionCode": 12,
        "markers": {"disable": false, "remove": false, "update": false},
        "path": "/data/adb/modules/vowifi_sw",
        "iccid": "8986000000000000001"
      },
      "root": {"capable": true},
      "config": {
        "count": 1,
        "files": ["/data/adb/vowifi/config/8986000000000000001.json"]
      },
      "observer": {
        "packagePid": true,
        "processRecord": true,
        "foreground": true,
        "pid": 19364
      },
      "engine": {
        "instanceCount": 2,
        "configMissingCount": 0,
        "stoppedCount": 1,
        "startingCount": 0,
        "runningCount": 1,
        "stoppingCount": 0,
        "iccid": "8986000000000000001",
        "pid": 4242,
        "imsi": "460001234567890",
        "msisdn": "13800138000"
      },
      "wfc": {"readable": true, "global": 1},
      "secret": "8986000000000000001"
    }
""".trimIndent()

internal fun healthyJson(
    ok: Boolean = true,
    installed: Boolean = true,
    disable: Boolean = false,
    remove: Boolean = false,
    update: Boolean = false,
    capable: Boolean = true,
    configCount: Int = 1,
    packagePid: Boolean = true,
    processRecord: Boolean = true,
    foreground: Boolean = true,
    instanceCount: Int = 1,
    runningCount: Int = 1,
    stoppedCount: Int = 0,
    startingCount: Int = 0,
    stoppingCount: Int = 0,
    configMissingCount: Int = 0,
    wfc: String? = """{"readable":true,"global":1}""",
    includeOk: Boolean = true,
): String {
    val okField = if (includeOk) """"ok": $ok,""" else ""
    val wfcField = wfc?.let { ""","wfc":$it""" } ?: ""
    return """
        {
          $okField
          "module": {
            "installed": $installed,
            "id": "vowifi_sw",
            "version": "0.3.9",
            "versionCode": 12,
            "markers": {"disable": $disable, "remove": $remove, "update": $update}
          },
          "root": {"capable": $capable},
          "config": {"count": $configCount},
          "observer": {
            "packagePid": $packagePid,
            "processRecord": $processRecord,
            "foreground": $foreground
          },
          "engine": {
            "instanceCount": $instanceCount,
            "configMissingCount": $configMissingCount,
            "stoppedCount": $stoppedCount,
            "startingCount": $startingCount,
            "runningCount": $runningCount,
            "stoppingCount": $stoppingCount
          }
          $wfcField
        }
    """.trimIndent()
}
