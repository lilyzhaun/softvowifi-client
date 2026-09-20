package com.vowifi.sw.manager.data

import java.security.MessageDigest
import java.util.Base64

internal actual fun messageRouteDigest(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
