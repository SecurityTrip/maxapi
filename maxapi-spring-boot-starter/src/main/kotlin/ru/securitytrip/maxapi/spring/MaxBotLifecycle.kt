package ru.securitytrip.maxapi.spring

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.logging.LogFactory
import org.springframework.context.SmartLifecycle
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.client.UnauthorizedException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Starts the bot with the application context and stops it with the context.
 *
 * - On start, unless `max.bot.mode=none` without `max.bot.sync-commands`: checks the token with
 *   `getMyInfo`, which also fills [MaxBot.username]; shows the bot menu if `max.bot.sync-commands` is set;
 *   in webhook mode subscribes to `max.bot.webhook.url` if it is set. A rejected token fails the start
 *   with an explanation. When the API cannot be reached, answers with an error or does not finish these
 *   steps within `max.bot.startup-timeout`, the application still starts: the failure is logged and the
 *   steps are retried in the background, with pauses from one second up to five minutes, until the
 *   lifecycle stops.
 * - Then in polling mode it starts long polling, and with a webhook URL it checks every
 *   `max.bot.webhook.check-interval` that the subscription still exists and subscribes again if Max
 *   dropped it (see [MaxBot.ensureWebhook]).
 * - On stop: in polling mode stops polling and lets the updates already received be handled; the
 *   shutdown of the context waits for them up to `spring.lifecycle.timeout-per-shutdown-phase`, after
 *   which closing the bot cancels the handlers. The webhook subscription is kept, so updates wait for
 *   the next start.
 */
public class MaxBotLifecycle(private val bot: MaxBot, private val properties: MaxBotProperties) : SmartLifecycle {
    @Volatile
    private var running = false

    @Volatile
    private var polling: Job? = null

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var webhookCheck: ScheduledFuture<*>? = null

    /** Pause before the first retry of the startup steps; shorter in tests. */
    internal var retryDelay: Duration = Duration.ofSeconds(1)

    @Volatile
    private var prepared = false

    override fun start() {
        if (properties.mode != MaxBotProperties.Mode.NONE || properties.syncCommands) {
            try {
                runBlocking { withTimeout(properties.startupTimeout.toMillis()) { prepare() } }
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: Exception) {
                log.warn("Cannot reach the Max API on startup (${e.message}); retrying in the background", e)
                retry(scheduler(), retryDelay.toMillis())
            }
        }
        if (properties.mode == MaxBotProperties.Mode.POLLING) polling = bot.start()
        val url = properties.webhook.url
        val interval = properties.webhook.checkInterval.toMillis()
        if (properties.mode == MaxBotProperties.Mode.WEBHOOK && url != null && interval > 0) {
            val secret = properties.webhookSecret()
            webhookCheck = scheduler().scheduleWithFixedDelay({ check(url, secret) }, interval, interval, TimeUnit.MILLISECONDS)
        }
        running = true
    }

    /** The steps that need the API; each one that succeeded is not repeated. */
    private suspend fun prepare() {
        if (bot.username == null) bot.username = bot.client.getMyInfo().username
        if (properties.syncCommands) bot.syncCommands()
        if (properties.mode == MaxBotProperties.Mode.WEBHOOK) {
            properties.webhook.url?.let { bot.setWebhook(it, properties.webhookSecret()) }
        }
        prepared = true
    }

    /**
     * Retries the startup steps on [executor] after [delayMillis]. Once [stop] shut the executor down,
     * nothing is scheduled any more, also by a retry that was running then.
     */
    internal fun retry(executor: ScheduledExecutorService, delayMillis: Long) {
        try {
            executor.schedule({ retryNow(executor, delayMillis) }, delayMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            log.debug("The bot stopped; no more retries")
        }
    }

    private fun retryNow(executor: ScheduledExecutorService, delayMillis: Long) {
        try {
            runBlocking { prepare() }
            log.info("Reached the Max API: the bot is ready")
        } catch (e: UnauthorizedException) {
            log.error("The Max API rejected max.bot.token; the bot cannot work until the token is fixed", e)
        } catch (e: Exception) {
            val next = minOf(delayMillis * 2, MAX_RETRY_DELAY)
            log.warn("Cannot reach the Max API (${e.message}); retrying in ${next / 1000} s")
            retry(executor, next)
        }
    }

    private fun scheduler(): ScheduledExecutorService = synchronized(this) {
        scheduler ?: Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "max-bot-lifecycle").apply { isDaemon = true }
        }.also { scheduler = it }
    }

    /** One check of the subscription; errors are logged, and the next check tries again. */
    internal fun check(url: String, secret: String?) {
        try {
            bot.ensureWebhookBlocking(url, secret)
        } catch (e: Exception) {
            log.warn("Cannot check the webhook subscription to $url: ${e.message}", e)
        }
    }

    /** Stops polling and runs [callback] once the updates already received are handled. */
    override fun stop(callback: Runnable) {
        synchronized(this) {
            scheduler?.shutdownNow()
            scheduler = null
            webhookCheck = null
        }
        bot.stop()
        val job = polling
        polling = null
        running = false
        if (job == null) callback.run() else job.invokeOnCompletion { callback.run() }
    }

    /** Like `stop(callback)`, waiting up to `max.bot.shutdown-timeout`. */
    override fun stop() {
        val done = CountDownLatch(1)
        stop { done.countDown() }
        if (!done.await(properties.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("Updates were still being handled after ${properties.shutdownTimeout}; closing the bot cancels them")
        }
    }

    override fun isRunning(): Boolean = running

    /** Whether the subscription is being checked periodically. */
    internal val isCheckingWebhook: Boolean get() = webhookCheck != null

    /** Whether the startup steps that need the API have succeeded. */
    internal val isPrepared: Boolean get() = prepared

    private companion object {
        const val MAX_RETRY_DELAY = 5 * 60 * 1000L
    }
}

private val log = LogFactory.getLog(MaxBotLifecycle::class.java)
