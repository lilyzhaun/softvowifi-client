package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class MessageStorageException : Exception(STORAGE_NOTICE) {
    override fun toString(): String = "MessageStorageException"
}

internal const val STORAGE_NOTICE = "无法确认短信已保存，未向引擎发送。"
internal const val STORAGE_SYNC_NOTICE = "短信可能已提交，但无法保存最新状态，请勿重复发送。"
internal const val STORAGE_OPEN_NOTICE = "无法打开短信存储"
internal const val MESSAGE_IDENTITY_NOTICE = "无法确认当前 SIM 身份"

internal fun isStorageAttachmentNotice(notice: String): Boolean =
    notice == STORAGE_NOTICE || notice == STORAGE_SYNC_NOTICE

internal object HmacProbeCodec {
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(stored: String): ByteArray {
        val padded = stored + "=".repeat((4 - stored.length % 4) % 4)
        return try {
            Base64.UrlSafe.decode(padded)
        } catch (_: Exception) {
            throw MessageStorageException()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(probe: ByteArray): String = Base64.UrlSafe.encode(probe).trimEnd('=')
}

internal fun requireCredentialEncryptedStorage(isDeviceProtectedStorage: Boolean) {
    if (isDeviceProtectedStorage) throw MessageStorageException()
}

interface MessageRepository {
    suspend fun changeConversation(identity: StoredMessageConversation, action: MessageConversationAction): Boolean = throw MessageStorageException()
    val history: MessageHistoryRepository
    suspend fun unreadNotificationPreview(identity: StoredMessageConversation): List<MessageRecord> = emptyList()
    suspend fun ownerLabels(owners: List<SimOwnerId>): Map<SimOwnerId, MessageOwnerLabel> = throw MessageStorageException()
    suspend fun saveOwnerRemark(owner: SimOwnerId, remark: MessageOwnerRemark): MessageOwnerLabel = throw MessageStorageException()
    suspend fun listConversations(): List<StoredMessageConversation>
    suspend fun listConversation(identity: StoredMessageConversation): List<MessageRecord> =
        listByOwner(identity.owner).filter { PeerNumberNormalizer.normalize(it.peerLabel) == identity.key }
    suspend fun insertQueued(
        ownerId: SimOwnerId,
        messageId: String,
        peerAddress: String,
        body: String,
        nowMs: Long,
    )

    suspend fun bindAccepted(
        ownerId: SimOwnerId,
        messageId: String,
        engineSmsId: String,
        nowMs: Long,
    )

    suspend fun applyOutgoingStatus(
        ownerId: SimOwnerId,
        engineSmsId: String,
        status: SmsEngineStatus,
        nowMs: Long,
    )

    suspend fun insertIncomingIfAbsent(
        ownerId: SimOwnerId,
        messageId: String,
        engineSmsId: String,
        peerAddress: String,
        body: String,
        messageAtMs: Long,
        nowMs: Long,
    ): IncomingInsertResult

    suspend fun markIncomingRead(
        request: ObservedIncomingReadRequest,
        nowMs: Long,
    ): IncomingReadUpdate

    suspend fun markFailed(ownerId: SimOwnerId, messageId: String, nowMs: Long)

    suspend fun markUnknown(ownerId: SimOwnerId, messageId: String, nowMs: Long)

    suspend fun interruptLeftoverQueued(ownerId: SimOwnerId, nowMs: Long)

    suspend fun listByOwner(ownerId: SimOwnerId): List<MessageRecord>

    suspend fun countUnreadIncoming(): Long

    suspend fun countUnreadByOwner(): Map<SimOwnerId, Long> = throw MessageStorageException()

    suspend fun lastOwner(): SimOwnerId?

    suspend fun setLastOwner(ownerId: SimOwnerId)

    suspend fun readHmacProbe(): ByteArray?

    suspend fun writeHmacProbe(probe: ByteArray)

    fun close()
}
