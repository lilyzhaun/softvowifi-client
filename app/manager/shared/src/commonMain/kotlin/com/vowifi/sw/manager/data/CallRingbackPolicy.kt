package com.vowifi.sw.manager.data

internal const val EARLY_MEDIA_PRESENCE_MILLIS = 1_000L

internal fun hasRingbackSuppressingPcm(pcm: ByteArray): Boolean = pcm.any { it != 0.toByte() }

internal fun shouldPlayLocalRingback(ringing: Boolean, lastMediaAt: Long?, now: Long): Boolean =
    ringing && (lastMediaAt == null || now - lastMediaAt >= EARLY_MEDIA_PRESENCE_MILLIS)
