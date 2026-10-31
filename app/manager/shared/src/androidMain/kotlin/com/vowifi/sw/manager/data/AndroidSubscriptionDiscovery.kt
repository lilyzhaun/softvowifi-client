package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.SubscriptionDiscovery

internal class AndroidSubscriptionDiscovery(
    context: Context,
    private val diagnostic: (DiagnosticEvent) -> Unit = { AndroidDiagnosticLog.emit(it) },
    private val handles: OpaqueIccidHandleStore? = null,
) {
    // The inventory constructs an Android subscription listener. Create it with
    // the source owner, not on the IO thread used by subsequent read-only loads.
    private val inventory = AndroidSubscriptionInventory(context.applicationContext)

    fun discover(configIccids: List<String>): List<SubscriptionDiscovery> {
        return try {
            val active = (inventory.listActive() as? SubscriptionInventoryResult.Active)?.subscriptions
                ?: return observed(configIccids.map { SubscriptionDiscovery.Unavailable })
            handles?.reconcileActive(active)
            if (active.isEmpty()) {
                return observed(configIccids.map { SubscriptionDiscovery.Missing })
            }
            observed(configIccids.map { configIccid ->
                val expected = IccidNormalizer.normalize(configIccid)
                    ?: return@map SubscriptionDiscovery.Missing
                val info = active.firstOrNull { candidate ->
                    candidate.key == expected
                } ?: return@map SubscriptionDiscovery.Missing
                SubscriptionDiscovery.Matched(
                    homeCarrierLabel(info.homeCarrierLabel),
                    validatedSlotIndex(info.slotIndex),
                    info.mcc,
                    info.mnc,
                )
            })
        } catch (_: SecurityException) {
            observed(configIccids.map { SubscriptionDiscovery.Unavailable })
        } catch (_: IllegalStateException) {
            observed(configIccids.map { SubscriptionDiscovery.Unavailable })
        } catch (_: RuntimeException) {
            observed(configIccids.map { SubscriptionDiscovery.Unavailable })
        }
    }

    private fun observed(rows: List<SubscriptionDiscovery>): List<SubscriptionDiscovery> {
        val unavailable = rows.any { it is SubscriptionDiscovery.Unavailable }
        val missing = rows.any { it is SubscriptionDiscovery.Missing }
        diagnostic(DiagnosticEvent(DiagnosticComponent.CONFIGURATION, if (unavailable) DiagnosticLevel.WARN else DiagnosticLevel.INFO,
            DiagnosticName.OBSERVED, DiagnosticStage.CONFIG_MATCH,
            if (unavailable) DiagnosticResult.UNAVAILABLE else DiagnosticResult.OBSERVED,
            when { unavailable -> DiagnosticReason.UNAVAILABLE; missing -> DiagnosticReason.NOT_ACTIVE; rows.isEmpty() -> DiagnosticReason.EMPTY; else -> DiagnosticReason.NONE },
            count = rows.size.coerceAtMost(1_000_000_000), accepted = rows.count { it is SubscriptionDiscovery.Matched }.coerceAtMost(1_000_000_000)))
        return rows
    }
}
