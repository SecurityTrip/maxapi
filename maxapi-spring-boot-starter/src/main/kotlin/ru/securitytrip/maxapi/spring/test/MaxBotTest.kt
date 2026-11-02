package ru.securitytrip.maxapi.spring.test

import org.springframework.test.context.TestPropertySource
import java.lang.annotation.Inherited

/**
 * Lets a Spring Boot test start the application context without a real bot token and without the
 * network (since 1.5.0): sets `max.bot.token` to a fake value, `max.bot.mode=none` and
 * `max.bot.sync-commands=false`, so the bot and its handlers are configured but nothing calls the API
 * on startup. Feed updates with `MaxBot.processUpdate`, or use `BotTester` of `maxapi-test` for handlers.
 *
 * ```kotlin
 * @SpringBootTest
 * @MaxBotTest
 * class ApplicationTest {
 *     @Test
 *     fun contextLoads() {}
 * }
 * ```
 *
 * Needs `spring-test` on the test classpath, as every Spring Boot test has.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Inherited
@TestPropertySource(properties = ["max.bot.token=test-token", "max.bot.mode=none", "max.bot.sync-commands=false"])
public annotation class MaxBotTest
