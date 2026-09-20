package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal interface MessageNotificationSink {
    fun isActive(handle: ConversationHandle): Boolean
    fun post(handle: ConversationHandle, count: Int, alert: Boolean, presentation: MessageNotificationPresentation?)
    fun cancel(handle: ConversationHandle)
}

internal class MessageNotificationEffects(
    private val sink: MessageNotificationSink,
    private val allowed: (ConversationHandle) -> Boolean,
    private val denied: () -> Unit,
    private val dismissed: (ConversationHandle, MessageNotificationPresentation?) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private sealed interface Change {
        val handle: ConversationHandle
        data class Post(
            override val handle: ConversationHandle,
            val count: Int,
            val alert: Boolean,
            val allowCreate: Boolean,
            val presentation: MessageNotificationPresentation?,
            val cancelFirst: Boolean,
        ) : Change
        data class Cancel(override val handle: ConversationHandle) : Change
    }
    private class Flight(val change: Change, var invalidated: Boolean = false, var writing: Boolean = false)
    private val lock = Any()
    private val pending = linkedMapOf<ConversationHandle, Change>()
    private val contentHandles = linkedSetOf<ConversationHandle>()
    private var flight: Flight? = null
    private var running = false
    private var idle = CompletableDeferred(Unit)

    fun post(handle: ConversationHandle, count: Int, alert: Boolean, allowCreate: Boolean, presentation: MessageNotificationPresentation?) = synchronized(lock) {
        val previous = pending[handle] ?: flight?.takeIf { !it.writing && !it.invalidated && it.change.handle == handle }?.change
        val previousPost = previous as? Change.Post
        enqueue(Change.Post(handle, count, alert || previousPost?.alert == true,
            allowCreate || previousPost?.allowCreate == true, presentation,
            previous is Change.Cancel || previousPost?.cancelFirst == true))
    }

    fun cancel(handle: ConversationHandle) = synchronized(lock) { enqueue(Change.Cancel(handle)) }

    fun invalidatePosts(handles: Set<ConversationHandle>? = null) = synchronized(lock) {
        pending.values.filterIsInstance<Change.Post>().filter { handles == null || it.handle in handles }.forEach {
            if (it.cancelFirst) pending[it.handle] = Change.Cancel(it.handle) else pending.remove(it.handle)
        }
        contentHandles.removeAll { handles == null || it in handles }
        flight?.takeIf { it.change is Change.Post && (handles == null || it.change.handle in handles) }?.invalidated = true
    }

    suspend fun awaitIdle() { synchronized(lock) { idle }.await() }

    private fun enqueue(change: Change) {
        pending.remove(change.handle)
        contentHandles.remove(change.handle)
        pending[change.handle] = change
        if (change is Change.Post && change.presentation != null) contentHandles.add(change.handle)
        if (contentHandles.size > 32) {
            val oldest = contentHandles.first()
            contentHandles.remove(oldest)
            pending[oldest] = (pending.getValue(oldest) as Change.Post).copy(presentation = null)
        }
        if (!running) {
            running = true
            idle = CompletableDeferred()
            dispatcher.dispatch(EmptyCoroutineContext) {
                try { drain() } catch (failure: Throwable) {
                    synchronized(lock) {
                        pending.clear()
                        contentHandles.clear()
                        flight = null
                        running = false
                        idle.completeExceptionally(failure)
                    }
                    throw failure
                }
            }
        }
    }

    private fun drain() {
        while (true) {
            val current = synchronized(lock) {
                val next = pending.entries.firstOrNull()
                if (next == null) {
                    flight = null
                    running = false
                    idle.complete(Unit)
                    return
                }
                pending.remove(next.key)
                contentHandles.remove(next.key)
                Flight(next.value).also { flight = it }
            }
            try {
                when (val change = current.change) {
                    is Change.Cancel -> sink.cancel(change.handle)
                    is Change.Post -> {
                        if (change.cancelFirst) sink.cancel(change.handle)
                        deliver(current, change)
                    }
                }
            } catch (_: SecurityException) { denied() }
        }
    }

    private fun current(flight: Flight): Boolean = synchronized(lock) {
        !flight.invalidated && !pending.containsKey(flight.change.handle)
    }

    private fun deliver(flight: Flight, post: Change.Post) {
        if (!current(flight) || !allowed(post.handle)) return
        if (!post.allowCreate && !sink.isActive(post.handle)) {
            if (current(flight)) dismissed(post.handle, post.presentation)
            return
        }
        if (!allowed(post.handle)) return
        val write = synchronized(lock) {
            if (!current(flight)) false else { flight.writing = true; true }
        }
        if (!write) return
        sink.post(post.handle, post.count, post.alert, post.presentation)
        val compensate = synchronized(lock) { flight.invalidated && !pending.containsKey(post.handle) }
        if (compensate) sink.cancel(post.handle)
    }
}
