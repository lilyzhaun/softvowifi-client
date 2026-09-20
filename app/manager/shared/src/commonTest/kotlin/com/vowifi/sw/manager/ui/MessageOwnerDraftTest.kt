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

class MessageOwnerDraftTest {
    @Test fun partialDraftSurvivesRecreationWithoutMovingToAnotherOwner() = runTest {
        val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        val recomposer = Recomposer(coroutineContext)
        var registry = SaveableStateRegistry(null) { true }
        lateinit var draft: MessageOwnerDraft
        fun compose(owner: SimOwnerId?): Composition = Composition(NoNodesApplier(), recomposer).also {
            it.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    draft = rememberMessageOwnerDraft(owner)
                }
            }
        }
        fun recreate(previous: Composition, owner: SimOwnerId?): Composition {
            val saved = registry.performSave()
            previous.dispose()
            registry = SaveableStateRegistry(saved) { true }
            return compose(owner)
        }
        var composition = compose(ownerA)
        try {
            draft.updateRecipient("+")
            draft.updateBody("synthetic unfinished")
            composition = recreate(composition, ownerB)
            assertEquals("", draft.recipient)
            assertEquals("", draft.body)
            draft.updateRecipient("155502")
            draft.updateBody("synthetic B")
            composition = recreate(composition, ownerA)
            assertEquals("+", draft.recipient)
            assertEquals("synthetic unfinished", draft.body)
            composition = recreate(composition, null)
            assertEquals("", draft.body)
            draft.updateBody("unassigned")
            composition = recreate(composition, ownerB)
            assertEquals("155502", draft.recipient)
            assertEquals("synthetic B", draft.body)
            draft.clear()
            composition = recreate(composition, ownerA)
            assertEquals("synthetic unfinished", draft.body)
        } finally {
            composition.dispose()
            recomposer.close()
        }
    }

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
