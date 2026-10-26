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
        /** Starts the application with [properties] (`key=value`) and without a web server unless [web]. */
        fun start(vararg properties: String, web: Boolean = false): ConfigurableApplicationContext =
            SpringApplicationBuilder(SurveyApp::class.java)
                .web(if (web) WebApplicationType.SERVLET else WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", *properties)
                .run()
    }
}
