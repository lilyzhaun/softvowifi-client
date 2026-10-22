package com.vowifi.sw.manager.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.model.SimActiveJoinInput

internal class AndroidSubscriptionInventory @JvmOverloads constructor(
    context: Context,
    private val diagnostic: (DiagnosticEvent) -> Unit = { AndroidDiagnosticLog.emit(it) },
) : SubscriptionInventory {
    private val context = context.applicationContext
    private val lock = Any()
    private val watchers = mutableListOf<Watcher>()
    private var platformRegistered = false
    private var registerInFlight = false
    private val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            observe(DiagnosticStage.LISTENER, DiagnosticResult.OBSERVED)
            val snapshot = synchronized(lock) { watchers.toList() }
            snapshot.forEach { watcher ->
                ignoreWatchFailure { watcher.onChanged() }
            }
        }
    }

    override fun listActive(): SubscriptionInventoryResult = try {
        val manager = manager() ?: run {
            observe(DiagnosticStage.ENUMERATE, DiagnosticResult.UNAVAILABLE, DiagnosticReason.UNAVAILABLE)
            return SubscriptionInventoryResult.Failure(UNREADABLE)
        }
        val active = manager.activeSubscriptionInfoList.orEmpty()
        val accepted = active.mapNotNull(::toJoinInput)
        observe(DiagnosticStage.ENUMERATE, DiagnosticResult.OBSERVED,
            if (active.isEmpty()) DiagnosticReason.EMPTY else DiagnosticReason.NONE,
            count = active.size, accepted = accepted.size)
        SubscriptionInventoryResult.Active(accepted)
    } catch (_: SecurityException) {
        observe(DiagnosticStage.ENUMERATE, DiagnosticResult.FAILURE, DiagnosticReason.PERMISSION_DENIED)
        SubscriptionInventoryResult.PermissionDenied(PERMISSION)
    } catch (_: IllegalStateException) {
        observe(DiagnosticStage.ENUMERATE, DiagnosticResult.FAILURE, DiagnosticReason.UNAVAILABLE)
        SubscriptionInventoryResult.Failure(UNREADABLE)
    } catch (_: RuntimeException) {
        observe(DiagnosticStage.ENUMERATE, DiagnosticResult.FAILURE, DiagnosticReason.UNAVAILABLE)
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
            if (unregister) {
                val stopped = watchAttempt { removePlatformListener() }
                observe(DiagnosticStage.LISTENER, if (stopped is WatchAttempt.Ok) DiagnosticResult.STOPPED else DiagnosticResult.FAILURE,
                    when (stopped) { WatchAttempt.Ok -> DiagnosticReason.NONE; WatchAttempt.Denied -> DiagnosticReason.PERMISSION_DENIED; WatchAttempt.Failed -> DiagnosticReason.LISTENER_FAILED })
            }
        }
    }

    private fun addPlatformListener(): Boolean {
        val manager = manager() ?: run {
            observe(DiagnosticStage.LISTENER, DiagnosticResult.UNAVAILABLE, DiagnosticReason.UNAVAILABLE)
            return false
        }
        val result = watchAttempt {
            manager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
        }
        observe(DiagnosticStage.LISTENER, if (result is WatchAttempt.Ok) DiagnosticResult.SUCCESS else DiagnosticResult.FAILURE,
            when (result) { WatchAttempt.Ok -> DiagnosticReason.NONE; WatchAttempt.Denied -> DiagnosticReason.PERMISSION_DENIED; WatchAttempt.Failed -> DiagnosticReason.LISTENER_FAILED })
        return result is WatchAttempt.Ok
    }

    private fun removePlatformListener() {
        manager()?.removeOnSubscriptionsChangedListener(listener)
    }

    private fun manager(): SubscriptionManager? =
        context.getSystemService(SubscriptionManager::class.java)

    private fun toJoinInput(info: SubscriptionInfo): SimActiveJoinInput? {
        val key = IccidNormalizer.normalize(info.iccId) ?: run {
            // Existing SubscriptionInfo snapshot only; no additional telephony/SIM request.
            val raw = info.iccId
            val shape = raw.orEmpty().trimEnd('F', 'f')
            val reason = when {
                shape.isEmpty() -> DiagnosticReason.EMPTY
                shape.any { it !in '0'..'9' } -> DiagnosticReason.UNSUPPORTED_CHARACTERS
                else -> DiagnosticReason.INVALID_LENGTH
            }
            observe(DiagnosticStage.NORMALIZE, DiagnosticResult.FAILURE, reason, slot = info.simSlotIndex)
            return null
        }
        if (info.simSlotIndex !in 0..1) observe(DiagnosticStage.IDENTITY, DiagnosticResult.UNAVAILABLE, DiagnosticReason.INVALID_SLOT)
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

    private fun observe(stage: DiagnosticStage, result: DiagnosticResult, reason: DiagnosticReason = DiagnosticReason.NONE,
        slot: Int? = null, count: Int? = null, accepted: Int? = null) {
        diagnostic(DiagnosticEvent(DiagnosticComponent.SUBSCRIPTIONS,
            if (result == DiagnosticResult.FAILURE) DiagnosticLevel.WARN else DiagnosticLevel.INFO,
            if (stage == DiagnosticStage.NORMALIZE) DiagnosticName.REJECTED else DiagnosticName.OBSERVED,
            stage, result, reason, slot?.takeIf { it in 0..1 }, count?.coerceAtMost(1_000_000_000), accepted?.coerceAtMost(1_000_000_000)))
    }
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
