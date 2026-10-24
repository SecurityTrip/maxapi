package ru.securitytrip.maxapi.spring

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.springframework.context.SmartLifecycle
import ru.securitytrip.maxapi.bot.MaxBot

/**
 * Starts the bot with the application context and stops it with the context.
 *
 * - On start: shows the bot menu if `max.bot.sync-commands` is set; then in polling mode starts long
 *   polling, and in webhook mode subscribes to `max.bot.webhook.url` if it is set.
 * - On stop: in polling mode stops polling and waits until the updates already received are
 *   handled. The webhook subscription is kept, so updates wait for the next start.
 */
public class MaxBotLifecycle(private val bot: MaxBot, private val properties: MaxBotProperties) : SmartLifecycle {
    @Volatile
    private var running = false

    @Volatile
    private var polling: Job? = null

    override fun start() {
        if (properties.syncCommands) bot.syncCommandsBlocking()
        when (properties.mode) {
            MaxBotProperties.Mode.POLLING -> polling = bot.start()
            MaxBotProperties.Mode.WEBHOOK -> properties.webhook.url?.let { bot.setWebhookBlocking(it, properties.webhook.secret) }
            MaxBotProperties.Mode.NONE -> Unit
        }
        running = true
    }

    override fun stop() {
        bot.stop()
        polling?.let { runBlocking { it.join() } }
        polling = null
        running = false
    }

    override fun isRunning(): Boolean = running
}
