package com.vowifi.sw.manager.data

internal const val OPERATIONAL_LOG_SAFE_SUMMARY_MAX_LENGTH = 160

object OperationalLogRedactor {
    private val bracketedInstance = Regex("""(\]\s+\[)([^\]]*\d{15,}[^\]]*)(\]\s+)""")
    private val payloadContent = Regex(
        """(?i)\b(sms_body|sms_text|sms|body|payload|text)\s*[:=]\s*.*""",
    )
    private val networkAssignment = Regex(
        """(?i)\b(ip|ipv4|ipv6|addr|address|local|remote|pcscf|epdg)\b\s*[:=]\s*(?:\[[0-9a-fA-F:]+]|\S+)(?::\d+)?""",
    )
    private val labeledIdentity = Regex(
        """(?i)\b(iccid|imsi|msisdn|phone|impi|impu|pid|recipient|caller|callee|peer|from|to)\b\s*[:=]\s*\S*""",
    )
    private val uriIdentity = Regex("""(?i)\b(sip|tel):\S*""")
    private val secretValue = Regex(
        """(?i)\b(CK|IK|RES|AUTN|RAND|KASME|PSK|password|token|certificate)\b\s*[:=]\s*\S*""",
    )
    private val directIpv6Bracketed = Regex("""\[[0-9a-fA-F:]+](?::\d+)?""")
    private val directIpv6 = Regex(
        """\b(?:[0-9a-fA-F]{1,4}:){1,7}:[0-9a-fA-F]{1,4}\b|\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b|\b[0-9a-fA-F]{1,4}::\b|\b::[0-9a-fA-F]{1,4}\b""",
    )
    private val ipv4Address = Regex(
        """\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(?::\d+)?\b""",
    )
    private val directPhone = Regex(
        """(?:\+\d{1,4}[- ]?)(?:\(\d{1,4}\)[- ]?)?\d{1,4}(?:[- ]\d{1,4}){1,5}|\b(?:\(\d{1,4}\)[- ]?)?\d{2,4}[- ]\d{2,4}[- ]\d{3,4}(?:[- ]\d{3,4})*(?!\s*:)\b""",
    )
    private val privatePath = Regex("""(?:/data/|/proc/)\S+""")
    private val longHex = Regex("""(?i)\b[0-9a-f]{32,}\b""")
    private val longDigits = Regex("""\d{15,}""")

    fun redact(input: String): String = redactInternal(input, includeNetworkAddresses = true)

    private fun redactInternal(input: String, includeNetworkAddresses: Boolean): String {
        var output = bracketedInstance.replace(input) { match ->
            match.groupValues[1] + "•••• ${lastFour(match.groupValues[2])}" + match.groupValues[3]
        }
        output = payloadContent.replace(output, "[secret]")
        output = Regex("""(?i)\b(?:authorization|auth)\s*[:=]\s*.*""").replace(output, "[secret]")
        if (includeNetworkAddresses) {
            output = networkAssignment.replace(output, "[identity]")
        }
        output = labeledIdentity.replace(output, "[identity]")
        output = uriIdentity.replace(output, "[identity]")
        output = secretValue.replace(output, "[secret]")
        if (includeNetworkAddresses) {
            output = directIpv6Bracketed.replace(output, "[identity]")
            output = directIpv6.replace(output, "[identity]")
            output = ipv4Address.replace(output, "[identity]")
        }
        output = directPhone.replace(output, "[identity]")
        output = privatePath.replace(output, "[path]")
        output = longHex.replace(output, "[secret]")
        return longDigits.replace(output, "[identity]")
    }

    internal fun boundedSafeSummary(input: String): String = bounded(normalizedSafeText(input), OPERATIONAL_LOG_SAFE_SUMMARY_MAX_LENGTH)

    internal fun normalizedSafeText(input: String): String {
        val redacted = redact(input)
        val noPlaceholders = allowedWirePlaceholder.replace(redacted, " 敏感信息已隐藏 ")
        val collapsed = noPlaceholders
            .replace(Regex("""(?:敏感信息已隐藏\s*)+"""), "敏感信息已隐藏")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val clean = if (collapsed.isBlank() || collapsed == "敏感信息已隐藏") {
            "敏感信息已隐藏"
        } else {
            collapsed
        }
        return clean
    }

    internal fun bounded(clean: String, limit: Int): String {
        if (clean.length <= limit) return clean
        val end = if (clean[limit - 2].isHighSurrogate()) limit - 2 else limit - 1
        return clean.take(end).trimEnd() + "…"
    }

    internal fun displayLabel(instance: String): String {
        val suffix = Regex("""\d""").findAll(instance).map { it.value }.joinToString("").takeLast(4)
        return if (suffix.length == 4) "实例 •••• $suffix" else "实例 ••••"
    }

    internal fun changesMessage(message: String): Boolean = redact(message) != message

    internal fun requiresRedaction(value: String): Boolean {
        val labelScan = allowedWirePlaceholder.replace(value, "")
        return redact(value) != value ||
            sensitiveAssignment.containsMatchIn(labelScan) ||
            identityUriPrefix.containsMatchIn(labelScan) ||
            invalidWirePlaceholder.containsMatchIn(labelScan)
    }

    internal fun requiresWireRedaction(value: String): Boolean {
        val labelScan = allowedWirePlaceholder.replace(value, "")
        return redactInternal(value, includeNetworkAddresses = false) != value ||
            sensitiveUiAssignment.containsMatchIn(labelScan) ||
            identityUriPrefix.containsMatchIn(labelScan) ||
            invalidWirePlaceholder.containsMatchIn(labelScan)
    }

    internal fun isPrivateUiText(value: String): Boolean {
        val labelScan = allowedWirePlaceholder.replace(value, "")
        if (redactInternal(value, includeNetworkAddresses = false) != value) return false
        if (sensitiveUiAssignment.containsMatchIn(labelScan)) return false
        if (identityUriPrefix.containsMatchIn(labelScan)) return false
        if (invalidWirePlaceholder.containsMatchIn(labelScan)) return false
        if (allowedWirePlaceholder.containsMatchIn(value)) return false
        if (privatePath.containsMatchIn(value) || longHex.containsMatchIn(value) || longDigits.containsMatchIn(value)) {
            return false
        }
        return true
    }

    private fun lastFour(value: String): String =
        Regex("""\d""").findAll(value).map { it.value }.joinToString("").takeLast(4)

    private val sensitiveAssignment = Regex(
        """(?i)\b(iccid|imsi|msisdn|phone|impi|impu|pid|recipient|caller|callee|peer|from|to|ip|ipv4|ipv6|addr|address|local|remote|pcscf|epdg|sms|sms_body|sms_text|body|payload|text|ck|ik|res|autn|rand|kasme|psk|password|token|certificate)\b\s*[:=]""",
    )
    private val sensitiveUiAssignment = Regex(
        """(?i)\b(iccid|imsi|msisdn|phone|impi|impu|pid|recipient|caller|callee|peer|from|to|sms|sms_body|sms_text|body|payload|text|ck|ik|res|autn|rand|kasme|psk|password|token|certificate)\b\s*[:=]""",
    )
    private val identityUriPrefix = Regex("""(?i)\b(?:sip|tel):""")
    // Ordinary protocol sentences can contain these words. Reject placeholder-only
    // messages, noncanonical bracketed placeholders, and labeled private values.
    private val invalidWirePlaceholder = Regex(
        """(?im)\[(?:identity|secret)]|\b(?:identity|secret)\b["']?\s*[:=]|(?:^\s*|]\s+)(?:identity|secret)(?:\s+(?:identity|secret))*\s*$""",
    )
    private val allowedWirePlaceholder = Regex("""\[(?:identity|secret|path)]""")
}
