package com.vowifi.sw.manager.data

import kotlin.test.assertFalse

private val longDigitRun = Regex("""\d{15,}""")
private val longHexRun = Regex("""(?i)\b[0-9a-f]{32,}\b""")
private val sensitiveAssignment = Regex(
    """(?i)\b(iccid|imsi|msisdn|phone|impi|impu|pid|ck|ik|res|autn|rand|kasme|psk|password|token|certificate)\b\s*[:=]""",
)
private val identityUri = Regex("""(?i)\b(?:sip|tel):""")
private val wirePlaceholder = Regex("""\[(?:identity|secret|path)]""")

internal fun assertPrivate(text: String) {
    assertFalse(sensitiveAssignment.containsMatchIn(text), "leaked sensitive assignment")
    assertFalse(identityUri.containsMatchIn(text), "leaked identity URI")
    assertFalse(wirePlaceholder.containsMatchIn(text), "leaked wire placeholder")
    assertFalse(longDigitRun.containsMatchIn(text), "leaked digit run in $text")
    assertFalse(longHexRun.containsMatchIn(text), "leaked hexadecimal secret in $text")
    assertFalse(text.contains("/data/"), "leaked data path in $text")
    assertFalse(text.contains("/proc/"), "leaked proc path in $text")
}
