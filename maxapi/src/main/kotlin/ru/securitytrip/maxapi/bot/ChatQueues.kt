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
 * at once, a chat keeps at most `capacity` updates waiting, and all chats together `parallelism * capacity`.
 *
 * The worker of a chat runs in [scope] while its queue has updates and ends when the queue is empty.
 */
internal class ChatQueues(
    private val scope: CoroutineScope,
    parallelism: Int,
    private val capacity: Int,
    private val handle: suspend (Update) -> Unit,
) {
    private val running = Semaphore(parallelism)
    private val waiting = Semaphore(parallelism * capacity)
    private val queues = HashMap<Long, Channel<Delivery>>()
    private var closed = false

    /**
     * Queues [update], first waiting while all chats together have the most updates waiting.
     * `false` if the queue of its chat is full or the queues are closed; [done] then completes at once,
     * otherwise once the update is handled.
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
        val queued = synchronized(queues) {
            !closed && queues.getOrPut(key) { Channel<Delivery>(capacity).also { work(key, it) } }.trySend(delivery).isSuccess
        }
        if (!queued) finish(delivery)
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
                    finish(delivery)
                }
            }
        }
    }

    private fun finish(delivery: Delivery) {
        waiting.release()
        delivery.done?.complete(Unit)
    }

    /** An update on its way to a handler; [done] completes when it is handled, if the polling marker is kept. */
    private class Delivery(val update: Update, val done: CompletableDeferred<Unit>?)

    companion object {
        /** The queue of an update: its chat, or its user if the chat is unknown. */
        fun keyOf(update: Update): Long = update.chatId ?: update.user?.userId ?: 0L
    }
}
