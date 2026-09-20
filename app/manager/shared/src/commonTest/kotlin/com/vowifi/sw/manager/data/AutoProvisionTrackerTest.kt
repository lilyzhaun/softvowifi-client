package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SimActiveJoinInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AutoProvisionTrackerTest {
    @Test
    fun firstSnapshotSelectReturnsAllEligibleKeys() {
        val tracker = AutoProvisionTracker()

        val selected = tracker.select(
            active = listOf(eligible(SIM_KEY_A, 0), eligible(SIM_KEY_B, 1)),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(listOf(SIM_KEY_A, SIM_KEY_B), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun sameSnapshotSelectReturnsNoneAfterAttemptsEvenIfStillMissing() {
        val tracker = AutoProvisionTracker()
        val active = listOf(eligible(SIM_KEY_A, 0), eligible(SIM_KEY_B, 1))
        tracker.select(active, emptySet(), AutoProvisionRetry.Default)

        val selected = tracker.select(active, emptySet(), AutoProvisionRetry.Default)

        assertEquals(emptyList(), selected)
    }

    @Test
    fun changedSnapshotClearsAttemptsAndReturnsNewMissingCandidates() {
        val tracker = AutoProvisionTracker()
        tracker.select(listOf(eligible(SIM_KEY_A, 0)), emptySet(), AutoProvisionRetry.Default)

        val selected = tracker.select(
            active = listOf(eligible(SIM_KEY_A, 0), eligible(SIM_KEY_B, 1)),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(listOf(SIM_KEY_A, SIM_KEY_B), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun forceRetryClearsAttemptsAndRetriesMissingNotExisting() {
        val tracker = AutoProvisionTracker()
        val active = listOf(eligible(SIM_KEY_A, 0), eligible(SIM_KEY_B, 1))
        tracker.select(active, emptySet(), AutoProvisionRetry.Default)

        val selected = tracker.select(active, setOf(SIM_KEY_B), AutoProvisionRetry.ForceRetry)

        assertEquals(listOf(SIM_KEY_A), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun manualRetryRemovesOnlyThatKeyFromAttempts() {
        val tracker = AutoProvisionTracker()
        val active = listOf(eligible(SIM_KEY_A, 0), eligible(SIM_KEY_B, 1))
        tracker.select(active, emptySet(), AutoProvisionRetry.Default)

        val selected = tracker.select(active, emptySet(), AutoProvisionRetry.Manual(SIM_KEY_A))

        assertEquals(listOf(SIM_KEY_A), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun existingKeyIsNeverSelected() {
        val tracker = AutoProvisionTracker()

        val selected = tracker.select(
            active = listOf(eligible(SIM_KEY_A, 0), eligible(SIM_KEY_B, 1)),
            existingKeys = setOf(SIM_KEY_A),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(listOf(SIM_KEY_B), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun invalidMissingPlmnAndBlankKeysAreSkipped() {
        val tracker = AutoProvisionTracker()

        val selected = tracker.select(
            active = listOf(
                eligible(SIM_KEY_A, 0),
                simActive(SIM_KEY_B, 1, mcc = null, mnc = "01"),
                simActive(SIM_KEY_C, 2, mcc = "001", mnc = null),
                simActive("", 3),
                simActive(" ", 4),
                simActive("key-d", 5, mcc = "31", mnc = "01"),
            ),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(listOf(SIM_KEY_A), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun snapshotIdentityUsesSortedKeysAndIgnoresInputOrder() {
        val tracker = AutoProvisionTracker()
        tracker.select(
            active = listOf(eligible(SIM_KEY_B, 1), eligible(SIM_KEY_A, 0)),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        val selected = tracker.select(
            active = listOf(eligible(SIM_KEY_A, 0), eligible(SIM_KEY_B, 1)),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(emptyList(), selected)
    }

    @Test
    fun sameKeyBecomesEligibleWhenPlmnBecomesValid() {
        val tracker = AutoProvisionTracker()
        tracker.select(
            active = listOf(simActive(SIM_KEY_A, 0, mcc = null, mnc = null)),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        val selected = tracker.select(
            active = listOf(eligible(SIM_KEY_A, 0)),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(listOf(SIM_KEY_A), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun slotChangeClearsAttemptsForSameKey() {
        val tracker = AutoProvisionTracker()
        tracker.select(listOf(eligible(SIM_KEY_A, 0)), emptySet(), AutoProvisionRetry.Default)

        val selected = tracker.select(
            active = listOf(eligible(SIM_KEY_A, 1)),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(listOf(SIM_KEY_A), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun plmnChangeClearsAttemptsForSameKey() {
        val tracker = AutoProvisionTracker()
        tracker.select(listOf(eligible(SIM_KEY_A, 0)), emptySet(), AutoProvisionRetry.Default)

        val selected = tracker.select(
            active = listOf(simActive(SIM_KEY_A, 0, mcc = "456", mnc = "01")),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(listOf(SIM_KEY_A), selected.map(SimActiveJoinInput::key))
    }

    @Test
    fun snapshotStaysStableWhenSlotAndPlmnUnchanged() {
        val tracker = AutoProvisionTracker()
        tracker.select(
            active = listOf(eligible(SIM_KEY_B, 1), eligible(SIM_KEY_A, 0)),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        val selected = tracker.select(
            active = listOf(
                simActive(SIM_KEY_A, 0, mcc = "310", mnc = "260"),
                simActive(SIM_KEY_B, 1, mcc = "310", mnc = "260"),
            ),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(emptyList(), selected)
    }

    @Test
    fun oemBackedProfilesAreSelectedWhenMissing() {
        val tracker = AutoProvisionTracker()

        val selected = tracker.select(
            active = listOf(
                simActive(SIM_KEY_A, 0, mcc = "310", mnc = "260"),
                simActive(SIM_KEY_B, 1, mcc = "456", mnc = "01"),
                simActive(SIM_KEY_C, 0, mcc = "505", mnc = "03"),
                simActive("key-d", 1, mcc = "454", mnc = "03"),
                simActive("key-e", 0, mcc = "999", mnc = "99"),
            ),
            existingKeys = emptySet(),
            retry = AutoProvisionRetry.Default,
        )

        assertEquals(
            listOf(SIM_KEY_A, SIM_KEY_B, SIM_KEY_C, "key-d", "key-e"),
            selected.map(SimActiveJoinInput::key),
        )
    }

    @Test
    fun trackerToStringOmitsRawKeys() {
        val tracker = AutoProvisionTracker()
        tracker.select(listOf(eligible(SIM_ICCID_19, 0)), emptySet(), AutoProvisionRetry.Default)

        val dumped = tracker.toString() + AutoProvisionRetry.Manual(SIM_ICCID_19).toString()

        assertFalse(dumped.contains(SIM_ICCID_19))
        assertPrivate(dumped)
    }
}

private fun eligible(
    key: String,
    slotIndex: Int,
) = simActive(key, slotIndex, mcc = "310", mnc = "260")
