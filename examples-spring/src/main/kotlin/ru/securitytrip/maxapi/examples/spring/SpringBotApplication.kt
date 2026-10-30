package ru.securitytrip.maxapi.examples.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Service
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.spring.MaxBotConfigurer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spring Boot bot: the starter creates the client and the bot from `application.yaml`, includes every
 * `Router` bean, calls every `MaxBotConfigurer` bean and starts long polling with the application.
 */
@SpringBootApplication
open class SpringBotApplication {
    @Bean
    open fun greetings(): MaxBotConfigurer = MaxBotConfigurer { bot ->
        bot.onBotStarted { reply("Hi! Send /time, /long, /buttons, /app or /contact") }
    }

    @Bean
    open fun timeRouter(clock: TimeService): Router {
        val presses = AtomicInteger()
        return Router()
            .onCommand("time", "Current server time") { reply("Server time: ${clock.now()}") }
            // Longer than one message: replyLong sends it as several, keeping the bold text bold.
            .onCommand("long", "A text longer than one message") { replyLong(message { markdown(longText()) }) }
            .onCommand("buttons", "A button to press") {
                reply { text("Press the button"); keyboard { row { callback("Press", "press") } } }
            }
            .onCallback("press") {
                editMessage(message { text("Pressed ${presses.incrementAndGet()} times"); keyboard { row { callback("Press", "press") } } })
            }
            .onMessage { reply("Send /time, /long, /buttons, /app or /contact") }
    }

    private fun longText(): String = (1..12).joinToString("\n\n") { n ->
        "**Paragraph $n.** " + "This sentence repeats to make the text longer than one message of Max. ".repeat(6).trim()
    }
}

@Service
class TimeService {
    fun now(): String = java.time.LocalTime.now().withNano(0).toString()
}

fun main(args: Array<String>) {
    runApplication<SpringBotApplication>(*args)
}
