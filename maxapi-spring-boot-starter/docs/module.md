# Module maxapi-spring-boot-starter

Spring Boot auto-configuration for maxapi bots. With `max.bot.token` set, the application gets a
`MaxClient` and a `MaxBot`; handlers come from
[MaxBotConfigurer][ru.securitytrip.maxapi.spring.MaxBotConfigurer] and `Router` beans, and the bot
receives updates by long polling or through a webhook endpoint, as
[MaxBotProperties][ru.securitytrip.maxapi.spring.MaxBotProperties] says.

# Package ru.securitytrip.maxapi.spring

Auto-configuration, properties and the lifecycle that starts and stops the bot with the application.
