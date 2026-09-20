package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SimActiveJoinInput
import com.vowifi.sw.manager.model.StandardProvision

internal sealed interface AutoProvisionRetry {
    data object Default : AutoProvisionRetry
    data object ForceRetry : AutoProvisionRetry
    class Manual(val key: String) : AutoProvisionRetry {
        override fun toString(): String = "Manual"
    }
}

internal class AutoProvisionTracker {
    private var lastSnapshot: List<SnapshotMember> = emptyList()
    private val attemptedKeys = LinkedHashSet<String>()

    fun select(
        active: List<SimActiveJoinInput>,
        existingKeys: Set<String>,
        retry: AutoProvisionRetry,
    ): List<SimActiveJoinInput> {
        val snapshot = snapshotIdentity(active)
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            attemptedKeys.clear()
        }
        when (retry) {
            AutoProvisionRetry.Default -> {}
            AutoProvisionRetry.ForceRetry -> attemptedKeys.clear()
            is AutoProvisionRetry.Manual -> attemptedKeys.remove(retry.key)
        }
        val selected = ArrayList<SimActiveJoinInput>()
        val seen = HashSet<String>()
        for (candidate in active) {
            val key = candidate.key
            if (key.isBlank() || !seen.add(key)) continue
            if (key in existingKeys || key in attemptedKeys) continue
            if (StandardProvision.automaticProfileFor(candidate.mcc, candidate.mnc) == null) continue
            selected += candidate
        }
        attemptedKeys += selected.map { it.key }
        return selected
    }

    override fun toString(): String = "AutoProvisionTracker"
}

private fun snapshotIdentity(active: List<SimActiveJoinInput>): List<SnapshotMember> =
    active
        .asSequence()
        .filter { it.key.isNotBlank() }
        .map { SnapshotMember(it.key, it.slotIndex, it.mcc, it.mnc) }
        .toSortedSet()
        .toList()

private class SnapshotMember(
    private val key: String,
    private val slotIndex: Int,
    private val mcc: String?,
    private val mnc: String?,
) : Comparable<SnapshotMember> {
    override fun compareTo(other: SnapshotMember): Int =
        compareValuesBy(this, other, { it.key }, { it.slotIndex }, { it.mcc }, { it.mnc })

    override fun equals(other: Any?): Boolean =
        other is SnapshotMember &&
            key == other.key &&
            slotIndex == other.slotIndex &&
            mcc == other.mcc &&
            mnc == other.mnc

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + slotIndex
        result = 31 * result + (mcc?.hashCode() ?: 0)
        result = 31 * result + (mnc?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "SnapshotMember"
}
