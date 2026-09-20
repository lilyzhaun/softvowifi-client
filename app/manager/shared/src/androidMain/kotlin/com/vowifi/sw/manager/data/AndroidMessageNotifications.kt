package com.vowifi.sw.manager.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class AndroidMessageNotifications internal constructor(
    context: Context,
    activityClass: Class<*>,
    smallIconRes: Int,
    sink: MessageNotificationSink?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : MessagePersistenceObserver {
    constructor(context: Context, activityClass: Class<*>, smallIconRes: Int) : this(context, activityClass, smallIconRes, null)
    private val context = context.applicationContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val lock = Any()
    private var visibleHandle: ConversationHandle? = null
    private var stopped = false
    private var reconciling = false
    private val platform = AndroidMessageNotificationSink(this.context, activityClass, smallIconRes)
    private val previews = MessageNotificationPreviews(lock, ::updatePreview)
    private val policy = MessageNotificationPolicy(::post, ::cancel)
    @Volatile private var permitted = platform.canPost()
    private val access = MutableStateFlow<Boolean?>(permitted)
    val enabled: StateFlow<Boolean?> = access
    private val effects = MessageNotificationEffects(sink ?: platform,
        allowed = { _ -> synchronized(lock) { !stopped && permitted } },
        denied = { synchronized(lock) { permitted = false; access.value = false } },
        dismissed = { handle, presentation -> synchronized(lock) {
            if (presentation != null && previews.presentation(handle) === presentation) previews.remove(handle)
        } }, dispatcher = dispatcher)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "短信", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) = synchronized(lock) {
        if (stopped) return@synchronized
        val changed = previews.snapshot(owner, records, freshIncomingId)
        policy.snapshot(owner, records, freshIncomingId)
        changed.filter { it != visibleHandle }.forEach { handle ->
            val rows = records.filterIsInstance<MessageRecord.Incoming>().filter {
                !it.isRead && messageConversationHandle(owner, PeerNumberNormalizer.normalize(it.peerLabel)) == handle
            }
            if (freshIncomingId !in rows.map { it.id }) post(handle, rows.size, false)
        }
    }

    fun visible(handle: ConversationHandle?) = synchronized(lock) {
        if (handle != visibleHandle) {
            val affected = setOfNotNull(visibleHandle, handle)
            previews.invalidateLookups(affected)
            effects.invalidatePosts(affected)
        }
        visibleHandle = handle
        policy.visible(handle)
    }
    override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) = synchronized(lock) {
        if (stopped) return@synchronized
        val lookup = previews.unread(conversation, ids)
        policy.threadUnread(conversation, ids)
        lookup?.start()
    }
    override fun onRead(owner: SimOwnerId, ids: Set<String>) = synchronized(lock) {
        if (stopped) return@synchronized
        val lookups = previews.read(owner, ids)
        policy.read(owner, ids)
        lookups.forEach { it.start() }
    }
    override fun onThreadSnapshot(conversation: StoredMessageConversation, records: List<MessageRecord>) = synchronized(lock) {
        if (stopped) return@synchronized
        val changed = previews.thread(conversation, records)
        policy.threadSnapshot(conversation, records)
        if (changed && conversation.handle != visibleHandle) {
            post(conversation.handle, previews.count(conversation.handle), false)
        }
    }

    internal fun bindPreviewLookup(scope: CoroutineScope, lookup: suspend (StoredMessageConversation) -> List<MessageRecord>) = synchronized(lock) {
        stopped = false
        previews.bind(scope, lookup)
    }

    internal fun unbindPreviewLookup() = synchronized(lock) {
        stopped = true
        previews.clear()
        effects.invalidatePosts()
    }

    fun permissionsChanged() {
        val allowed = platform.canPost()
        synchronized(lock) {
            val recovered = allowed && !permitted
            permitted = allowed
            access.value = allowed
            if (!allowed) effects.invalidatePosts()
            if (recovered && !stopped) {
                reconciling = true
                try { policy.reconcile() } finally { reconciling = false }
            }
        }
    }

    fun openSettings() {
        val intent = if (manager.areNotificationsEnabled()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun post(handle: ConversationHandle, count: Int, alert: Boolean) {
        if (permitted) effects.post(handle, count, alert, alert || reconciling, previews.presentation(handle))
    }

    private fun updatePreview(handle: ConversationHandle, count: Int) {
        if (stopped || handle == visibleHandle) return
        post(handle, count, false)
    }

    private fun cancel(handle: ConversationHandle) {
        previews.remove(handle)
        effects.cancel(handle)
    }

    internal suspend fun awaitEffects() = effects.awaitIdle()

    companion object {
        const val CHANNEL = "messages"
        const val ACTION = "com.vowifi.sw.OPEN_MESSAGE"
        const val EXTRA_HANDLE = "message_conversation"

        private fun routeUri(handle: ConversationHandle): Uri = Uri.parse("softvowifi-message:${handle.value}")

        fun consume(intent: Intent): ConversationHandle? {
            if (intent.action != ACTION) return null
            val raw = intent.getStringExtra(EXTRA_HANDLE)
            val keys = intent.extras?.keySet()
            val handle = raw?.let(ConversationHandle::parse)
            val valid = handle != null && keys == setOf(EXTRA_HANDLE) && intent.data == routeUri(handle)
            intent.removeExtra(EXTRA_HANDLE)
            intent.action = null
            intent.data = null
            return if (valid) handle else null
        }
    }
}
