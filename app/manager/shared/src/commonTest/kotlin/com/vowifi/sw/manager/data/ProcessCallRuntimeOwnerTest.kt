package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessCallRuntimeOwnerTest {
    @Test
    fun serviceFirstThenActivityResolvesSameRuntimeIdentity() {
        val factory = CountingFactory()
        val owner = ProcessCallRuntimeOwner(factory::create)
        val service = owner.acquire(true, ProcessRuntimeStart.Service).requireReady()
        val activity = owner.acquire(true, ProcessRuntimeStart.Activity).requireReady()
        assertTrue(service.createdNow)
        assertFalse(activity.createdNow)
        assertSame(service.runtime, activity.runtime)
        assertSame(service.runtime.pool, activity.runtime.pool)
        assertSame(service.runtime.calls, activity.runtime.calls)
        assertSame(service.runtime.history, activity.runtime.history)
        assertEquals(1, factory.calls)
        assertEquals(1, owner.factoryInvocationCount())
    }

    @Test
    fun applicationFirstThenActivityResolvesSameRuntimeIdentity() {
        val factory = CountingFactory()
        val owner = ProcessCallRuntimeOwner(factory::create)
        val application = owner.acquire(true, ProcessRuntimeStart.Application).requireReady()
        val activity = owner.acquire(true, ProcessRuntimeStart.Activity).requireReady()
        assertSame(application.runtime, activity.runtime)
        assertEquals(1, factory.calls)
    }

    @Test
    fun activityFirstThenServiceResolvesSameRuntimeIdentity() {
        val factory = CountingFactory()
        val owner = ProcessCallRuntimeOwner(factory::create)
        val activity = owner.acquire(true, ProcessRuntimeStart.Activity).requireReady()
        val service = owner.acquire(true, ProcessRuntimeStart.Service).requireReady()
        assertSame(activity.runtime, service.runtime)
        assertFalse(service.createdNow)
        assertEquals(1, factory.calls)
    }

    @Test
    fun repeatedAcquireIsIdempotent() {
        val factory = CountingFactory()
        val owner = ProcessCallRuntimeOwner(factory::create)
        val first = owner.acquire(true, ProcessRuntimeStart.Application).requireReady()
        repeat(3) {
            val again = owner.acquire(true, ProcessRuntimeStart.Activity).requireReady()
            assertFalse(again.createdNow)
            assertSame(first.runtime, again.runtime)
        }
        assertEquals(1, factory.calls)
        assertEquals(1, owner.factoryInvocationCount())
    }

    @Test
    fun activityDisposeClosesOnlyActivitySourcesNeverProcessOwned() {
        val factory = CountingFactory()
        val owner = ProcessCallRuntimeOwner(factory::create)
        val ready = owner.acquire(true, ProcessRuntimeStart.Activity).requireReady()
        val closed = closeActivityOwned(ready.runtime)
        assertEquals(ACTIVITY_OWNED_KINDS, closed)
        assertEquals(PROCESS_OWNED_KINDS, ProcessRuntimeHandleKind.entries.filterNot(::closesOnActivityDispose).toSet())
        assertFalse(ready.runtime.pool.closed)
        assertFalse(ready.runtime.calls.closed)
        assertFalse(ready.runtime.history.closed)
        assertTrue(ready.runtime.observer.closed)
        assertTrue(ready.runtime.engine.closed)
        assertFalse(ready.runtime.messages.closed)
        val again = owner.acquire(true, ProcessRuntimeStart.Activity).requireReady()
        assertSame(ready.runtime, again.runtime)
        assertEquals(1, factory.calls)
    }

    @Test
    fun secondProcessCallSourceFactoryInvocationIsGuarded() {
        val guard = ProcessCallSourceGuard()
        val first = guard.createOnce { FakeCalls() }
        val error = assertFailsWith<DuplicateProcessCallSourceException> {
            guard.createOnce { FakeCalls() }
        }
        assertEquals("DuplicateProcessCallSourceException", error.toString())
        assertEquals(DUPLICATE_PROCESS_CALL_SOURCE_NOTICE, error.message)
        assertFalse(first.closed)
        assertFalse(error.toString().contains("sip:"))
        assertFalse(error.toString().contains("raw:"))
    }

    @Test
    fun credentialNotReadyDoesNotInitializeHistoryOrCalls() {
        val factory = CountingFactory()
        val owner = ProcessCallRuntimeOwner(factory::create)
        val locked = owner.acquire(false, ProcessRuntimeStart.Service)
        assertEquals(ProcessRuntimeAcquire.CredentialNotReady, locked)
        assertEquals(0, factory.calls)
        assertEquals(0, owner.factoryInvocationCount())
        assertFalse(isCredentialEncryptedStorageReady(userUnlocked = false, deviceProtectedStorage = false))
        assertFalse(isCredentialEncryptedStorageReady(userUnlocked = true, deviceProtectedStorage = true))
        assertTrue(isCredentialEncryptedStorageReady(userUnlocked = true, deviceProtectedStorage = false))
        val unlocked = owner.acquire(true, ProcessRuntimeStart.Service).requireReady()
        assertTrue(unlocked.createdNow)
        assertEquals(1, factory.calls)
        assertFalse(unlocked.runtime.pool.closed)
        assertFalse(unlocked.runtime.calls.closed)
        assertFalse(unlocked.runtime.history.closed)
    }

    @Test
    fun factoryFailureIsBoundedAndRetryCreatesOnce() {
        val factory = CountingFactory(failuresBeforeSuccess = 1)
        val owner = ProcessCallRuntimeOwner(factory::create)
        val failed = owner.acquire(true, ProcessRuntimeStart.Application)
        assertIs<ProcessRuntimeAcquire.Failed>(failed)
        assertEquals("ProcessRuntimeInitException", failed.error.toString())
        assertEquals(PROCESS_RUNTIME_INIT_NOTICE, failed.error.message)
        assertFalse(failed.error.toString().contains("synthetic"))
        assertEquals(1, factory.calls)
        val ready = owner.acquire(true, ProcessRuntimeStart.Application).requireReady()
        assertTrue(ready.createdNow)
        assertEquals(2, factory.calls)
        val again = owner.acquire(true, ProcessRuntimeStart.Activity).requireReady()
        assertSame(ready.runtime, again.runtime)
        assertFalse(again.createdNow)
        assertEquals(2, factory.calls)
        assertEquals(2, owner.factoryInvocationCount())
    }

    @Test
    fun ownerDoesNotTreatDuplicateCallSourceAsSilentFailedRetry() {
        val guard = ProcessCallSourceGuard()
        val owner = ProcessCallRuntimeOwner {
            guard.createOnce { FakeRuntime() }
        }
        val first = owner.acquire(true, ProcessRuntimeStart.Application).requireReady()
        assertFailsWith<DuplicateProcessCallSourceException> {
            guard.createOnce { FakeRuntime() }
        }
        val again = owner.acquire(true, ProcessRuntimeStart.Activity).requireReady()
        assertSame(first.runtime, again.runtime)
        assertEquals(1, owner.factoryInvocationCount())
    }
}

private val PROCESS_OWNED_KINDS = setOf(
    ProcessRuntimeHandleKind.ENGINE_POOL,
    ProcessRuntimeHandleKind.CALL_SOURCE,
    ProcessRuntimeHandleKind.CALL_HISTORY,
    ProcessRuntimeHandleKind.NOTIFICATION_COORDINATOR,
    ProcessRuntimeHandleKind.MESSAGE_SOURCE,
)

private val ACTIVITY_OWNED_KINDS = setOf(
    ProcessRuntimeHandleKind.OBSERVER_LOG,
    ProcessRuntimeHandleKind.ENGINE_CONTROL,
    ProcessRuntimeHandleKind.SIM_CONFIGURATION,
    ProcessRuntimeHandleKind.DIAGNOSTICS,
    ProcessRuntimeHandleKind.OPERATIONAL_LOG,
)

private fun <Runtime : Any> ProcessRuntimeAcquire<Runtime>.requireReady(): ProcessRuntimeAcquire.Ready<Runtime> {
    return when (this) {
        is ProcessRuntimeAcquire.Ready -> this
        ProcessRuntimeAcquire.CredentialNotReady -> error("expected ready")
        is ProcessRuntimeAcquire.Failed -> error("expected ready")
    }
}

private fun closeActivityOwned(runtime: FakeRuntime): Set<ProcessRuntimeHandleKind> {
    val closed = mutableSetOf<ProcessRuntimeHandleKind>()
    ProcessRuntimeHandleKind.entries.forEach { kind ->
        if (!closesOnActivityDispose(kind)) {
            return@forEach
        }
        closed += kind
        when (kind) {
            ProcessRuntimeHandleKind.OBSERVER_LOG -> runtime.observer.close()
            ProcessRuntimeHandleKind.ENGINE_CONTROL -> runtime.engine.close()
            ProcessRuntimeHandleKind.SIM_CONFIGURATION -> runtime.simConfiguration.close()
            ProcessRuntimeHandleKind.MESSAGE_SOURCE -> runtime.messages.close()
            ProcessRuntimeHandleKind.DIAGNOSTICS -> runtime.diagnostics.close()
            ProcessRuntimeHandleKind.OPERATIONAL_LOG -> runtime.logs.close()
            ProcessRuntimeHandleKind.ENGINE_POOL,
            ProcessRuntimeHandleKind.CALL_SOURCE,
            ProcessRuntimeHandleKind.CALL_HISTORY,
            ProcessRuntimeHandleKind.NOTIFICATION_COORDINATOR,
            -> error("process-owned handle closed")
        }
    }
    return closed
}

private class CountingFactory(private var failuresBeforeSuccess: Int = 0) {
    var calls = 0

    fun create(): FakeRuntime {
        calls += 1
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess -= 1
            error("synthetic factory failure")
        }
        return FakeRuntime()
    }
}

private class FakeCloseable {
    var closed = false
    fun close() {
        closed = true
    }
}

private class FakeCalls {
    var closed = false
}

private class FakeRuntime(
    val pool: FakeCloseable = FakeCloseable(),
    val calls: FakeCloseable = FakeCloseable(),
    val history: FakeCloseable = FakeCloseable(),
    val observer: FakeCloseable = FakeCloseable(),
    val engine: FakeCloseable = FakeCloseable(),
    val simConfiguration: FakeCloseable = FakeCloseable(),
    val messages: FakeCloseable = FakeCloseable(),
    val diagnostics: FakeCloseable = FakeCloseable(),
    val logs: FakeCloseable = FakeCloseable(),
)
