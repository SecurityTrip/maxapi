# Module maxapi-ktor

Ktor server routing for maxapi webhooks: `routing { maxWebhook("/max/webhook", bot, secret) }`, and the
`MaxWebApp` plugin that checks the `initData` of mini apps.

# Package ru.securitytrip.maxapi.ktor

[maxWebhook][ru.securitytrip.maxapi.ktor.maxWebhook] accepts webhook deliveries and passes them to a bot.
[MaxWebApp][ru.securitytrip.maxapi.ktor.MaxWebApp] answers 401 to calls without valid mini app `initData`, and
[maxInitData][ru.securitytrip.maxapi.ktor.maxInitData] returns the checked data to handlers.
