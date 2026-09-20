package com.vowifi.sw.manager.data

object PeerNumberNormalizer {
    fun normalize(peer: String): String {
        val digitStart = if (peer.startsWith('+')) 1 else 0
        val digitCount = peer.length - digitStart
        if (digitCount in MIN_DIGITS..MAX_DIGITS) {
            for (index in digitStart until peer.length) {
                if (peer[index] !in '0'..'9') return rawKey(peer)
            }
            return if (digitStart == 0) peer else peer.substring(1)
        }
        return rawKey(peer)
    }

    private fun rawKey(peer: String): String = "raw:$peer"
}

private const val MIN_DIGITS = 1
private const val MAX_DIGITS = 20
