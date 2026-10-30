package ru.securitytrip.maxapi.it

import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import ru.securitytrip.maxapi.bot.Filters
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.bot.and

/** A small survey bot: a dialog of two steps kept in the configured storage. */
@SpringBootApplication
open class SurveyApp {
    @Bean
    open fun survey(): Router = Router()
        .onCommand("survey") {
            setState("name")
            reply("What is your name?")
        }
        .onMessage(Filters.state("name") and Filters.hasText()) {
            setState("rating", mapOf("name" to text!!))
            reply("Nice to meet you, $text! Rate us from 1 to 5")
        }
        .onMessage(Filters.state("rating")) {
            reply("Thanks, ${stateData["name"]}: $text")
            clearState()
        }
        .onMessage { reply("echo: $text") }

    companion object {
        /** Starts the application with [properties] (`key=value`) and a web server of [web], none by default. */
        fun start(vararg properties: String, web: WebApplicationType = WebApplicationType.NONE): ConfigurableApplicationContext =
            SpringApplicationBuilder(SurveyApp::class.java)
                .web(web)
                .properties("spring.main.banner-mode=off", *properties)
                .run()
    }
}
