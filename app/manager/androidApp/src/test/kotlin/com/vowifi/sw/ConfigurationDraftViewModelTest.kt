package com.vowifi.sw

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ConfigurationDraftViewModelTest {
    @Test fun activityRecreationKeepsItsViewModelMemory() {
        Robolectric.buildActivity(DraftActivity::class.java).use { controller ->
            controller.setup()
            val first = controller.get()
            val memory = ViewModelProvider(first)[ConfigurationDraftViewModel::class.java].memory
            controller.recreate()
            val recreated = controller.get()
            assertNotSame(first, recreated)
            assertSame(memory, ViewModelProvider(recreated)[ConfigurationDraftViewModel::class.java].memory)
        }
    }

    @Test fun retainedViewModelStoreReusesOnlyMemoryAndNewStoreDiscardsIt() {
        val retainedStore = ViewModelStore()
        fun model(store: ViewModelStore): ConfigurationDraftViewModel {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
            return ViewModelProvider(owner, ViewModelProvider.NewInstanceFactory())[ConfigurationDraftViewModel::class.java]
        }
        val first = model(retainedStore)
        assertSame(first.memory, model(retainedStore).memory)
        val newProcess = ViewModelStore()
        assertNotSame(first.memory, model(newProcess).memory)
        retainedStore.clear()
        newProcess.clear()
    }

    class DraftActivity : androidx.activity.ComponentActivity()
}
