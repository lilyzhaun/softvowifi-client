package com.vowifi.sw.manager.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageSearchRestorationTest {
    @Test
    fun querySurvivesDisposalAndSavedStateRestoration() = runTest {
        val recomposer = Recomposer(coroutineContext)
        val registry = SaveableStateRegistry(null) { true }
        lateinit var query: MutableState<String>
        fun compose(stateRegistry: SaveableStateRegistry): Composition =
            Composition(NoNodesApplier(), recomposer).also { composition ->
                composition.setContent {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides stateRegistry) {
                        query = rememberMessageSearchQuery()
                    }
                }
            }
        val first = compose(registry)
        assertEquals("", query.value)
        query.value = "测试查询"
        val saved = registry.performSave()
        first.dispose()
        val restored = compose(SaveableStateRegistry(saved) { true })
        try {
            assertEquals("测试查询", query.value)
        } finally {
            restored.dispose()
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
