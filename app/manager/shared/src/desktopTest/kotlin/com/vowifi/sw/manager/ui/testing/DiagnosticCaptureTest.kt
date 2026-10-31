package com.vowifi.sw.manager.ui.testing

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticCaptureTest {
    @Test fun disabledCaptureDoesNotEvaluateTheWriter() {
        var writes = 0
        captureTestDiagnostics(enabled = false) { writes++ }
        assertEquals(0, writes, "默认回归不应进行诊断编码或写盘")
    }

    @Test fun enabledCaptureEvaluatesTheWriterExactlyOnce() {
        var writes = 0
        captureTestDiagnostics(enabled = true) { writes++ }
        assertEquals(1, writes)
    }

    @Test fun processDefaultRequiresExplicitOptIn() {
        var writes = 0
        captureTestDiagnostics { writes++ }
        assertEquals(if (System.getenv("SOFTVOWIFI_TEST_CAPTURE") == "1") 1 else 0, writes)
    }
}
