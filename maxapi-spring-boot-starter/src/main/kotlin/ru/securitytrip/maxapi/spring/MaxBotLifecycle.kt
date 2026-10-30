package ru.securitytrip.maxapi.spring

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.apache.commons.logging.LogFactory
import org.springframework.context.SmartLifecycle
import ru.securitytrip.maxapi.bot.MaxBot
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Starts the bot with the application context and stops it with the context.
 *
 * - On start: shows the bot menu if `max.bot.sync-commands` is set; then in polling mode starts long
 *   polling, and in webhook mode subscribes to `max.bot.webhook.url` if it is set. With that URL, it
 *   also checks every `max.bot.webhook.check-interval` that the subscription still exists and
 *   subscribes again if Max dropped it (see [MaxBot.ensureWebhook]).
 * - On stop: in polling mode stops polling and waits until the updates already received are
 *   handled. The webhook subscription is kept, so updates wait for the next start.
 */
public class MaxBotLifecycle(private val bot: MaxBot, private val properties: MaxBotProperties) : SmartLifecycle {
    @Volatile
    private var running = false

    @Volatile
    private var polling: Job? = null

    @Volatile
    private var watchdog: ScheduledExecutorService? = null

    override fun start() {
        if (properties.syncCommands) bot.syncCommandsBlocking()
        when (properties.mode) {
            MaxBotProperties.Mode.POLLING -> polling = bot.start()
            MaxBotProperties.Mode.WEBHOOK -> properties.webhook.url?.let { subscribe(it, properties.webhook.secret) }
            MaxBotProperties.Mode.NONE -> Unit
        }
        running = true
    }

    private fun subscribe(url: String, secret: String?) {
        bot.setWebhookBlocking(url, secret)
        val interval = properties.webhook.checkInterval.toMillis()
        if (interval <= 0) return
        watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "max-webhook-check").apply { isDaemon = true }
        }.apply {
            scheduleWithFixedDelay({ check(url, secret) }, interval, interval, TimeUnit.MILLISECONDS)
        }
    }

    /** One check of the subscription; errors are logged, and the next check tries again. */
    internal fun check(url: String, secret: String?) {
        try {
            bot.ensureWebhookBlocking(url, secret)
        } catch (e: Exception) {
            log.warn("Cannot check the webhook subscription to $url: ${e.message}", e)
        }
    }

    override fun stop() {
        watchdog?.shutdownNow()
        watchdog = null
        bot.stop()
        polling?.let { runBlocking { it.join() } }
        polling = null
        running = false
    }

    override fun isRunning(): Boolean = running

    /** Whether the subscription is being checked periodically. */
    internal val isCheckingWebhook: Boolean get() = watchdog != null
}

private val log = LogFactory.getLog(MaxBotLifecycle::class.java)
