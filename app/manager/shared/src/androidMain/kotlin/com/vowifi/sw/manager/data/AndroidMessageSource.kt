package com.vowifi.sw.manager.data

import android.content.Context
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.MessageUiState
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

class AndroidMessageSource(
    pool: AndroidEngineClientPool,
    context: Context,
    parentContext: CoroutineContext = Dispatchers.IO,
    clock: () -> Long = { System.currentTimeMillis() },
    private val notifications: AndroidMessageNotifications? = null,
) : MessageSource {
    private val visibility = MessageVisibility({ notifications?.visible(it) }, ::markVisibleRead)
    private val opened: Opened?
    private val previewScope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]))
    private val fallback = MutableStateFlow<MessageUiState>(MessageUiState.Failure(STORAGE_OPEN_NOTICE))

    init {
        opened = try {
            val hmac = AndroidSimOwnerHmac()
            val names = AndroidHistoryLineNames(context, hmac::ownerIdFor, knownKeys = { historyKeys() }, homeMcc = ::configuredHomeMcc)
            val repository = AndroidMessageRepository.open(context, hmac, lineNames = names)
            notifications?.bindPreviewLookup(previewScope, repository::unreadNotificationPreview)
            Opened(
                repository = repository,
                source = EngineMessageSource(
                    runtime = EngineMessageRuntime(
                        listInstances = ::listInstances,
                        transportFor = { iccid -> AndroidEngineMessageTransport(pool, iccid) },
                        clock = clock,
                        activeIccids = { activeMessageIccids(context) },
                        lineLabelFor = { callLineLabel(context, it).takeIf { label -> label.startsWith("SIM ") } },
                    ),
                    parentContext = parentContext,
                    repository = repository,
                    ownerIdFor = hmac::ownerIdFor,
                    nextMessageId = { UUID.randomUUID().toString() },
                    observer = notifications ?: MessagePersistenceObserver { _, _, _ -> },
                ),
            )
        } catch (_: Exception) {
            notifications?.unbindPreviewLookup()
            previewScope.cancel()
            null
        }
        opened?.source?.watchAttachment()
    }

    override val state: StateFlow<MessageUiState> = opened?.source?.state ?: fallback
    override val unreadSummary get() = opened?.source?.unreadSummary ?: super.unreadSummary
    override val historyChoices get() = opened?.source?.historyChoices ?: super.historyChoices
    override suspend fun changeConversation(handle: String, action: MessageConversationAction) =
        opened?.source?.changeConversation(handle, action) ?: MessageConversationActionResult.FAILED
    override suspend fun saveOwnerRemark(owner: SimOwnerId, input: String) =
        opened?.source?.saveOwnerRemark(owner, input) ?: MessageRemarkSaveResult.Failed
    override fun selectHistoryOwner(owner: SimOwnerId) { opened?.source?.selectHistoryOwner(owner) }
    override val notificationsEnabled: StateFlow<Boolean?> get() = notifications?.enabled ?: super.notificationsEnabled
    override fun openNotificationSettings() { notifications?.openSettings() }
    override fun selectConversation(handle: String?) { opened?.source?.selectConversation(handle) }
    override fun searchHistory(query: String) { opened?.source?.searchHistory(query) }
    override fun loadMoreHistory() { opened?.source?.loadMoreHistory() }
    override fun loadOlderMessages() { opened?.source?.loadOlderMessages() }
    override fun sendFromDisplayedOwner(owner: SimOwnerId?, number: String, text: String) {
        opened?.source?.sendFromDisplayedOwner(owner, number, text)
    }

    override fun refresh() {
        opened?.source?.refresh()
    }

    override fun sendSms(number: String, text: String) {
        opened?.source?.sendSms(number, text)
    }

    override fun markVisibleRead(request: ObservedIncomingReadRequest) {
        opened?.source?.markVisibleRead(request)
    }

    fun setResumed(value: Boolean) {
        visibility.setResumed(value)
        if (value) notifications?.permissionsChanged()
    }

    override fun observeVisible(handle: com.vowifi.sw.manager.ui.navigation.ConversationHandle?, ids: List<String>) {
        visibility.observe(state.value, handle, ids)
    }

    override fun close() {
        notifications?.unbindPreviewLookup()
        previewScope.cancel()
        opened?.source?.close()
        opened?.repository?.close()
    }

    private suspend fun listInstances(): List<EngineInstanceState> = withContext(Dispatchers.IO) {
        val result = try {
            AndroidSuCommand().run("$CONTROL_SCRIPT list")
        } catch (failure: IOException) {
            if (failure.message.isPermissionDenied()) {
                throw MessagePermissionException(PERMISSION_MESSAGE)
            }
            throw IllegalStateException(LIST_FAILURE)
        }
        when {
            result.timedOut -> error(LIST_FAILURE)
            result.exitCode != 0 && result.output.isPermissionDenied() ->
                throw MessagePermissionException(PERMISSION_MESSAGE)
            result.exitCode != 0 -> error(LIST_FAILURE)
            else -> EngineControlScriptCodec.instances(result.output)
        }
    }

    private suspend fun historyKeys(): List<String> = try {
        listInstances().map { it.iccid }
    } catch (_: MessagePermissionException) { emptyList() }
    catch (_: IllegalStateException) { emptyList() }
    catch (_: IllegalArgumentException) { emptyList() }

    private data class Opened(
        val repository: MessageRepository,
        val source: EngineMessageSource,
    )

    private companion object {
        const val CONTROL_SCRIPT = "/data/adb/modules/vowifi_sw/control.sh"
        const val LIST_FAILURE = "无法读取短信会话"
        const val PERMISSION_MESSAGE = "请在 KernelSU 中授予 SoftVoWiFi root 权限"
    }
}

private fun String?.isPermissionDenied(): Boolean =
    this.orEmpty().contains("EACCES", ignoreCase = true) ||
        this.orEmpty().contains("Permission denied", ignoreCase = true) ||
        this.orEmpty().contains("error=13", ignoreCase = true)

internal fun activeMessageIccids(context: Context): List<String?>? = try {
    context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        ?.sortedBy { it.simSlotIndex.takeIf { slot -> slot >= 0 } ?: Int.MAX_VALUE }?.map {
        IccidNormalizer.normalize(it.iccId)
    }
} catch (_: SecurityException) {
    null
} catch (_: IllegalStateException) {
    null
}
