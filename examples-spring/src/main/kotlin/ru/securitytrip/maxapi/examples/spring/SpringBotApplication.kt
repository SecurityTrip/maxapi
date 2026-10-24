package ru.securitytrip.maxapi.examples.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Service
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.spring.MaxBotConfigurer

/**
 * Spring Boot bot: the starter creates the client and the bot from `application.yaml`, includes every
 * `Router` bean, calls every `MaxBotConfigurer` bean and starts long polling with the application.
 */
@SpringBootApplication
open class SpringBotApplication {
    @Bean
    open fun greetings(): MaxBotConfigurer = MaxBotConfigurer { bot ->
        bot.onBotStarted { reply("Hi! Send /time") }
    }

    @Bean
    open fun timeRouter(clock: TimeService): Router = Router()
        .onCommand("time", "Current server time") { reply("Server time: ${clock.now()}") }
        .onMessage { reply("Send /time") }
}

@Service
class TimeService {
    fun now(): String = java.time.LocalTime.now().withNano(0).toString()
}

fun main(args: Array<String>) {
    runApplication<SpringBotApplication>(*args)
}
