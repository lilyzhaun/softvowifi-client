package com.vowifi.sw.manager.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.model.SimActiveJoinInput

internal class AndroidSubscriptionInventory(
    context: Context,
) : SubscriptionInventory {
    private val context = context.applicationContext
    private val lock = Any()
    private val watchers = mutableListOf<Watcher>()
    private var platformRegistered = false
    private var registerInFlight = false
    private val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            val snapshot = synchronized(lock) { watchers.toList() }
            snapshot.forEach { watcher ->
                ignoreWatchFailure { watcher.onChanged() }
            }
        }
    }

    override fun listActive(): SubscriptionInventoryResult = try {
        val manager = manager() ?: return SubscriptionInventoryResult.Failure(UNREADABLE)
        val active = manager.activeSubscriptionInfoList.orEmpty()
        SubscriptionInventoryResult.Active(active.mapNotNull(::toJoinInput))
    } catch (_: SecurityException) {
        SubscriptionInventoryResult.PermissionDenied(PERMISSION)
    } catch (_: IllegalStateException) {
        SubscriptionInventoryResult.Failure(UNREADABLE)
    } catch (_: RuntimeException) {
        SubscriptionInventoryResult.Failure(UNREADABLE)
    }

    override fun startWatching(onChanged: () -> Unit): SubscriptionWatch {
        val watcher = Watcher(onChanged)
        val claimRegister = synchronized(lock) {
            watchers += watcher
            if (platformRegistered || registerInFlight) {
                false
            } else {
                registerInFlight = true
                true
            }
        }
        if (claimRegister) {
            val registered = addPlatformListener()
            val undo = synchronized(lock) {
                registerInFlight = false
                when {
                    watchers.isEmpty() -> {
                        if (registered) {
                            platformRegistered = false
                            true
                        } else {
                            false
                        }
                    }
                    registered -> {
                        platformRegistered = true
                        false
                    }
                    else -> false
                }
            }
            if (undo) ignoreWatchFailure { removePlatformListener() }
        }
        return SubscriptionWatch {
            val unregister = synchronized(lock) {
                if (!watchers.remove(watcher)) {
                    false
                } else if (watchers.isEmpty() && platformRegistered) {
                    platformRegistered = false
                    true
                } else {
                    false
                }
            }
            if (unregister) ignoreWatchFailure { removePlatformListener() }
        }
    }

    private fun addPlatformListener(): Boolean {
        val manager = manager() ?: return false
        return watchAttempt {
            manager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
        } is WatchAttempt.Ok
    }

    private fun removePlatformListener() {
        manager()?.removeOnSubscriptionsChangedListener(listener)
    }

    private fun manager(): SubscriptionManager? =
        context.getSystemService(SubscriptionManager::class.java)

    private fun toJoinInput(info: SubscriptionInfo): SimActiveJoinInput? {
        val key = IccidNormalizer.normalize(info.iccId) ?: return null
        return SimActiveJoinInput(
            key = key,
            slotIndex = info.simSlotIndex,
            carrierName = sanitizeCarrierLabel(
                info.carrierName?.toString(),
                info.displayName?.toString(),
            ),
            displayName = null,
            mcc = validatedMcc(info.mccString),
            mnc = validatedMnc(info.mncString),
            lastFour = fourDigitTail(key),
            homeCarrierLabel = subscriptionHomeCarrierLabel(context, info.subscriptionId),
        )
    }

    private class Watcher(val onChanged: () -> Unit)
}

private fun fourDigitTail(iccId: String): String? {
    if (iccId.length < 4) return null
    return iccId.substring(iccId.length - 4).takeIf(FOUR_DIGIT::matches)
}

private fun ignoreWatchFailure(block: () -> Unit) {
    if (watchAttempt(block) is WatchAttempt.Ok) return
}

private fun watchAttempt(block: () -> Unit): WatchAttempt = try {
    block()
    WatchAttempt.Ok
} catch (_: SecurityException) {
    WatchAttempt.Denied
} catch (_: IllegalStateException) {
    WatchAttempt.Failed
} catch (_: RuntimeException) {
    WatchAttempt.Failed
}

private sealed interface WatchAttempt {
    data object Ok : WatchAttempt
    data object Denied : WatchAttempt
    data object Failed : WatchAttempt
}

private const val PERMISSION = "请在 KernelSU 中授予 SoftVoWiFi root 权限"
private const val UNREADABLE = "无法读取 SIM 控制状态"
private val FOUR_DIGIT = Regex("""^\d{4}$""")
