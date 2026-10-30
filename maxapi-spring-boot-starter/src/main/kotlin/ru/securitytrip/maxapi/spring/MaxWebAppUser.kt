package ru.securitytrip.maxapi.spring

import ru.securitytrip.maxapi.webapp.WebAppData
import ru.securitytrip.maxapi.webapp.WebAppUser

/**
 * Marks a controller method parameter that receives the checked launch data of a mini app:
 *
 * ```kotlin
 * @GetMapping("/api/me")
 * fun me(@MaxWebAppUser user: WebAppUser) = "Hello, ${user.firstName}"
 *
 * @PostMapping("/api/order")
 * fun order(@MaxWebAppUser data: WebAppData, @RequestBody order: Order) = ...
 * ```
 *
 * The parameter may be a [WebAppUser] or the whole [WebAppData]. The mini app sends
 * `window.WebApp.initData` where `max.bot.web-app.source` says, by default in the `X-Max-Init-Data`
 * header; it is checked with `WebAppData.validate` and the token of the bot. Requests without valid
 * data are answered with 401 before the method is called.
 *
 * @property required `false` passes `null` when the request has no `initData` at all;
 *   invalid data is still answered with 401.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class MaxWebAppUser(val required: Boolean = true)
