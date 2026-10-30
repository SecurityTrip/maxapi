package ru.securitytrip.maxapi.examples.spring

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.model.attachment.ContactAttachment
import ru.securitytrip.maxapi.spring.MaxWebAppUser
import ru.securitytrip.maxapi.webapp.MaxLinks
import ru.securitytrip.maxapi.webapp.WebAppData
import ru.securitytrip.maxapi.webapp.WebAppPhone
import ru.securitytrip.maxapi.webapp.WebAppUser

/**
 * Backend of a mini app. The page in `static/index.html` sends `window.WebApp.initData` in the
 * `X-Max-Init-Data` header; `@MaxWebAppUser` parameters get the checked user, and requests without
 * valid data get 401 before the methods run.
 */
@RestController
@RequestMapping("/api")
class MiniAppController(@Value("\${max.bot.token}") private val botToken: String) {
    data class Profile(val id: Long, val name: String, val startParam: String?)

    @GetMapping("/me")
    fun me(@MaxWebAppUser data: WebAppData): Profile {
        val user = data.user!!
        return Profile(user.userId, listOfNotNull(user.firstName, user.lastName).joinToString(" "), data.startParam)
    }

    /** The result of `window.WebApp.requestContact()`, checked for the user who opened the mini app. */
    @PostMapping("/phone")
    fun phone(@MaxWebAppUser user: WebAppUser, @RequestBody phone: WebAppPhone): Map<String, String> {
        if (!phone.verify(user.userId, botToken)) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phone signature does not match")
        return mapOf("phone" to phone.phone)
    }
}

/** Bot side of the mini app: links that open it, and a contact shared with a `request_contact` button. */
@Configuration
open class MiniAppBot {
    // Before timeRouter, whose onMessage takes any message.
    @Bean
    @Order(1)
    open fun miniAppRouter(@Value("\${max.bot.token}") botToken: String): Router = Router()
        .onCommand("app", "Open the mini app") {
            val name = bot.username ?: client.getMyInfo().username!!.also { bot.username = it }
            reply {
                text("Open the mini app: ${MaxLinks.miniApp(name, "from_chat")}")
                keyboard { row { openApp("Open", name, payload = "from_button") } }
            }
        }
        .onCommand("contact", "Share your phone number") {
            reply { text("Press the button to share your contact"); keyboard { row { requestContact("Share contact") } } }
        }
        .onMessage({ ctx -> ctx.message.body?.attachments.orEmpty().any { it is ContactAttachment } }) {
            val contact = message.body!!.attachments!!.filterIsInstance<ContactAttachment>().first()
            // Only a contact shared with the bot's request_contact button is signed for this bot.
            reply(if (contact.verify(botToken)) "Your phone number is verified" else "This contact is not verified")
        }
}
