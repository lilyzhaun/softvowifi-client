package com.vowifi.sw.manager.data

internal const val PROCESS_RUNTIME_INIT_NOTICE = "无法初始化通话运行时"
internal const val DUPLICATE_PROCESS_CALL_SOURCE_NOTICE = "通话源已由进程持有"

internal class ProcessRuntimeInitException : Exception(PROCESS_RUNTIME_INIT_NOTICE) {
    override fun toString(): String = "ProcessRuntimeInitException"
}

internal class DuplicateProcessCallSourceException : Exception(DUPLICATE_PROCESS_CALL_SOURCE_NOTICE) {
    override fun toString(): String = "DuplicateProcessCallSourceException"
}

internal sealed interface ProcessRuntimeStart {
    data object Application : ProcessRuntimeStart
    data object Service : ProcessRuntimeStart
    data object Activity : ProcessRuntimeStart
}

internal sealed interface ProcessRuntimeAcquire<out Runtime : Any> {
    data class Ready<Runtime : Any>(
        val runtime: Runtime,
        val createdNow: Boolean,
        val start: ProcessRuntimeStart,
    ) : ProcessRuntimeAcquire<Runtime>

    data object CredentialNotReady : ProcessRuntimeAcquire<Nothing>

    data class Failed(val error: ProcessRuntimeInitException) : ProcessRuntimeAcquire<Nothing>
}

internal enum class ProcessRuntimeHandleKind {
    ENGINE_POOL,
    CALL_SOURCE,
    CALL_HISTORY,
    NOTIFICATION_COORDINATOR,
    OBSERVER_LOG,
    ENGINE_CONTROL,
    SIM_CONFIGURATION,
    MESSAGE_SOURCE,
    DIAGNOSTICS,
    OPERATIONAL_LOG,
}

internal fun isCredentialEncryptedStorageReady(
    userUnlocked: Boolean,
    deviceProtectedStorage: Boolean,
): Boolean = userUnlocked && !deviceProtectedStorage

internal fun closesOnActivityDispose(kind: ProcessRuntimeHandleKind): Boolean = when (kind) {
    ProcessRuntimeHandleKind.ENGINE_POOL,
    ProcessRuntimeHandleKind.CALL_SOURCE,
    ProcessRuntimeHandleKind.CALL_HISTORY,
    ProcessRuntimeHandleKind.NOTIFICATION_COORDINATOR,
    ProcessRuntimeHandleKind.MESSAGE_SOURCE,
    -> false
    ProcessRuntimeHandleKind.OBSERVER_LOG,
    ProcessRuntimeHandleKind.ENGINE_CONTROL,
    ProcessRuntimeHandleKind.SIM_CONFIGURATION,
    ProcessRuntimeHandleKind.DIAGNOSTICS,
    ProcessRuntimeHandleKind.OPERATIONAL_LOG,
    -> true
}

internal class ProcessCallSourceGuard {
    private val lock = Any()
    private var claimed = false

    fun <T : Any> createOnce(factory: () -> T): T = synchronized(lock) {
        if (claimed) throw DuplicateProcessCallSourceException()
        val created = factory()
        claimed = true
        created
    }
}

internal class ProcessCallRuntimeOwner<Runtime : Any>(
    private val factory: () -> Runtime,
) {
    private val lock = Any()
    private val guard = ProcessCallSourceGuard()
    private var runtime: Runtime? = null
    private var factoryCalls = 0

    fun acquire(
        credentialEncrypted: Boolean,
        start: ProcessRuntimeStart,
    ): ProcessRuntimeAcquire<Runtime> = synchronized(lock) {
        if (!credentialEncrypted) {
            return ProcessRuntimeAcquire.CredentialNotReady
        }
        runtime?.let { existing ->
            return ProcessRuntimeAcquire.Ready(
                runtime = existing,
                createdNow = false,
                start = start,
            )
        }
        factoryCalls += 1
        val created = try {
            guard.createOnce(factory)
        } catch (error: DuplicateProcessCallSourceException) {
            throw error
        } catch (_: Exception) {
            return ProcessRuntimeAcquire.Failed(ProcessRuntimeInitException())
        }
        runtime = created
        ProcessRuntimeAcquire.Ready(
            runtime = created,
            createdNow = true,
            start = start,
        )
    }

    fun factoryInvocationCount(): Int = synchronized(lock) { factoryCalls }
}
