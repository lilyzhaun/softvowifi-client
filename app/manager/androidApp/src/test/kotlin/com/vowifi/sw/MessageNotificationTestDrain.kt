@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidMessageNotifications
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal fun AndroidMessageNotifications.awaitNotifications() = runBlocking {
    withTimeout(5_000) { awaitEffects() }
}
