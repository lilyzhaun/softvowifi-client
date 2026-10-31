package com.vowifi.sw.manager.ui.testing

import java.nio.file.Path

/** Diagnostic encoding and disk output are separate from the actual regression assertions. */
internal inline fun captureTestDiagnostics(
    enabled: Boolean = System.getenv("SOFTVOWIFI_TEST_CAPTURE") == "1",
    write: () -> Unit,
) {
    if (enabled) write()
}

internal fun diagnosticCaptureDirectory(name: String): Path =
    Path.of(System.getenv("SOFTVOWIFI_TEST_CAPTURE_DIR") ?: "build/reports/diagnostic-captures").resolve(name)
