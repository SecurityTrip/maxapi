# Module maxapi-spring-boot-starter

Spring Boot auto-configuration for maxapi bots. With `max.bot.token` set, the application gets a
`MaxClient` and a `MaxBot`; handlers come from
[MaxBotConfigurer][ru.securitytrip.maxapi.spring.MaxBotConfigurer] and `Router` beans, and the bot
receives updates by long polling or through a webhook endpoint, as
[MaxBotProperties][ru.securitytrip.maxapi.spring.MaxBotProperties] says. Controllers of a mini app backend get the
checked user with [MaxWebAppUser][ru.securitytrip.maxapi.spring.MaxWebAppUser] parameters.

The webhook endpoint works in Spring MVC and Spring WebFlux applications. With Spring Boot Actuator the
health contributor `max` ([MaxBotHealthIndicator][ru.securitytrip.maxapi.spring.MaxBotHealthIndicator]
and the storage) is registered; with `maxapi-micrometer` and `maxapi-opentelemetry` the bot records
metrics and spans. [MaxRuntimeHints][ru.securitytrip.maxapi.spring.MaxRuntimeHints] prepares the models
for a GraalVM native image built with Spring AOT.

# Package ru.securitytrip.maxapi.spring

Auto-configuration, properties and the lifecycle that starts and stops the bot with the application.
