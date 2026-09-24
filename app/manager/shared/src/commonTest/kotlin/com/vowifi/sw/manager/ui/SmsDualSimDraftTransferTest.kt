package com.vowifi.sw.manager.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.vowifi.sw.manager.data.SimOwnerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsDualSimDraftTransferTest {
    @Test fun collisionRefusesOverwriteAndKeepsBothSavedDrafts() = runTest {
        Harness(Recomposer(coroutineContext)).use { harness ->
            harness.select(owner)
            harness.draft.updateRecipient("+12025550123")
            harness.draft.updateBody("synthetic owner draft")
            harness.select(null)
            harness.draft.updateRecipient("+12025550124")
            harness.draft.updateBody("synthetic unbound draft")
            assertFalse(harness.draft.bindUnassigned(owner))
            harness.select(owner)
            assertEquals("+12025550123", harness.draft.recipient)
            assertEquals("synthetic owner draft", harness.draft.body)
            harness.select(null)
            assertEquals("+12025550124", harness.draft.recipient)
            assertEquals("synthetic unbound draft", harness.draft.body)
        }
    }

    @Test fun explicitTransferMovesBothFieldsAndLeavesNoUnboundCopy() = runTest {
        Harness(Recomposer(coroutineContext)).use { harness ->
            harness.select(null)
            harness.draft.updateRecipient("+")
            harness.draft.updateBody("synthetic unfinished")
            assertTrue(harness.draft.bindUnassigned(owner))
            harness.select(owner)
            assertEquals("+", harness.draft.recipient)
            assertEquals("synthetic unfinished", harness.draft.body)
            harness.select(null)
            assertEquals("", harness.draft.recipient)
            assertEquals("", harness.draft.body)
        }
    }

    private class Harness(private val recomposer: Recomposer) : AutoCloseable {
        private var registry = SaveableStateRegistry(null) { true }
        private var composition: Composition? = null
        lateinit var draft: MessageOwnerDraft
        fun select(owner: SimOwnerId?) {
            val saved = registry.performSave()
            composition?.dispose()
            registry = SaveableStateRegistry(saved) { true }
            composition = Composition(NoNodesApplier(), recomposer).also {
                it.setContent { CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    draft = rememberMessageOwnerDraft(owner)
                } }
            }
        }
        override fun close() { composition?.dispose(); recomposer.close() }
    }

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    companion object {
        private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    }
}
