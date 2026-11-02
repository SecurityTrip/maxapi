package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.securitytrip.maxapi.model.update.Update

/**
 * Delivers updates to handlers with one queue per chat: updates of a chat are handled one at a time and
 * in order, while a slow or stuck handler holds up only its own chat. At most `parallelism` handlers run
 * at once ([running], shared by all queues of a bot), a chat keeps at most `capacity` updates waiting,
 * and all chats together `parallelism * capacity`.
 *
 * The worker of a chat runs in [scope] while its queue has updates and ends when the queue is empty.
 */
internal class ChatQueues(
    private val scope: CoroutineScope,
    private val running: Semaphore,
    parallelism: Int,
    private val capacity: Int,
    private val handle: suspend (Update) -> Unit,
) {
    private val waiting = Semaphore(parallelism * capacity)
    private val queues = HashMap<Long, Channel<Delivery>>()
    private var closed = false

    /**
     * Queues [update], first waiting while all chats together have the most updates waiting.
     * `false` if the queue of its chat is full or the queues are closed; [done] is then cancelled at once,
     * otherwise it completes once the update is handled.
     */
    suspend fun send(update: Update, done: CompletableDeferred<Unit>?): Boolean {
        waiting.acquire()
        return offer(Delivery(update, done))
    }

    /** Queues [update] without waiting; `false` if it cannot be queued now. */
    fun trySend(update: Update): Boolean = waiting.tryAcquire() && offer(Delivery(update, null))

    /** Accepts no more updates; the queued ones are still handled. */
    fun close() {
        synchronized(queues) { closed = true }
    }

    private fun offer(delivery: Delivery): Boolean {
        val key = keyOf(delivery.update)
        var created: Channel<Delivery>? = null
        val queued = synchronized(queues) {
            !closed && queues.getOrPut(key) { Channel<Delivery>(capacity).also { created = it } }.trySend(delivery).isSuccess
        }
        if (!queued) {
            waiting.release()
            delivery.done?.cancel()
        }
        // Started once the queue is in the map and has the update: a worker that runs at once (an immediate
        // dispatcher) must not find it empty and leave. Outside the lock, so that handlers never run under it.
        created?.let { work(key, it) }
        return queued
    }

    private fun work(key: Long, queue: Channel<Delivery>) {
        scope.launch(CoroutineName("max-bot-chat")) {
            while (true) {
                // Removed under the lock, so that offer never puts an update into a queue nobody reads.
                val delivery = synchronized(queues) { queue.tryReceive().getOrNull() ?: null.also { queues.remove(key) } }
                    ?: break
                try {
                    running.withPermit { handle(delivery.update) }
                } finally {
                    waiting.release()
                    delivery.done?.complete(Unit)
                }
            }
        }
    }

    /**
     * An update on its way to a handler, if the polling marker is kept: [done] completes when it is handled
     * and is cancelled when it is dropped.
     */
    private class Delivery(val update: Update, val done: CompletableDeferred<Unit>?)

    companion object {
        /** The queue of an update: its chat, or its user if the chat is unknown. */
        fun keyOf(update: Update): Long = update.chatId ?: update.user?.userId ?: 0L
    }
}
