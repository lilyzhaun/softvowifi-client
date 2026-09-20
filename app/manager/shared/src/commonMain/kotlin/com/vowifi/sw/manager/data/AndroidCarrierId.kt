package com.vowifi.sw.manager.data

internal object AndroidCarrierId {
    const val UNKNOWN = -1

    fun project(raw: Int?): Int? {
        if (raw == null || raw <= 0) return null
        return raw
    }

    override fun toString(): String = "AndroidCarrierId"
}
