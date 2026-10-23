package ru.securitytrip.maxapi

import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachment
import ru.securitytrip.maxapi.model.attachment.PhotoAttachment
import ru.securitytrip.maxapi.model.attachment.UnknownAttachment
import ru.securitytrip.maxapi.model.attachment.VideoUrls
import ru.securitytrip.maxapi.model.chat.Chat
import ru.securitytrip.maxapi.model.chat.ChatStatus
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.keyboard.LinkButton
import ru.securitytrip.maxapi.model.markup.StrongMarkup
import ru.securitytrip.maxapi.model.markup.UnknownMarkup
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.UnknownUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateList
import ru.securitytrip.maxapi.model.user.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {
    private val mapper = MaxJson.createMapper()

    @Test
    fun `parses bot_started example from the spec`() {
        val update = MaxJson.parseUpdate(
            """
            {
                "update_type": "bot_started",
                "timestamp": 1573226679188,
                "chat_id": 1234567890,
                "user": { "user_id": 1234567890, "name": "Boris", "username": "borisd84" },
                "payload": "any data meaningful to bot"
            }
            """,
        )
        assertIs<BotStartedUpdate>(update)
        assertEquals(1234567890L, update.chatId)
        assertEquals("borisd84", update.user.username)
        assertEquals("any data meaningful to bot", update.payload)
        assertEquals(1573226679188L, update.timestamp)
    }

    @Test
    fun `parses message with attachments, markup and unknown types`() {
        val update = MaxJson.parseUpdate(
            """
            {
              "update_type": "message_created",
              "timestamp": 10,
              "user_locale": "ru",
              "message": {
                "sender": { "user_id": 1, "first_name": "Ann", "is_bot": false },
                "recipient": { "chat_id": 100, "chat_type": "dialog", "user_id": 2 },
                "timestamp": 10,
                "body": {
                  "mid": "mid.1",
                  "seq": 7,
                  "text": "hello",
                  "attachments": [
                    { "type": "image", "payload": { "photo_id": 5, "token": "tok", "url": "https://img" } },
                    { "type": "inline_keyboard", "payload": { "buttons": [[
                      { "type": "callback", "text": "Yes", "payload": "yes" },
                      { "type": "link", "text": "Site", "url": "https://max.ru" },
                      { "type": "brand_new_button", "text": "?", "extra": 1 }
                    ]] } },
                    { "type": "hologram", "payload": { "depth": 3 } }
                  ],
                  "markup": [
                    { "type": "strong", "from": 0, "length": 5 },
                    { "type": "sparkle", "from": 1, "length": 2, "color": "gold" }
                  ]
                }
              }
            }
            """,
        )
        assertIs<MessageCreatedUpdate>(update)
        assertEquals(100L, update.chatId)
        assertEquals("Ann", update.user?.firstName)
        assertEquals("ru", update.userLocale)
        assertEquals(ChatType.DIALOG, update.message.recipient.chatType)

        val body = assertNotNullAnd(update.message.body)
        assertEquals("hello", body.text)
        val (photo, keyboard, unknown) = body.attachments!!
        assertEquals(5L, assertIs<PhotoAttachment>(photo).payload.photoId)

        val buttons = assertIs<InlineKeyboardAttachment>(keyboard).payload.buttons.single()
        assertEquals(CallbackButton("Yes", "yes"), buttons[0])
        assertEquals(LinkButton("Site", "https://max.ru"), buttons[1])
        assertEquals("brand_new_button", buttons[2].type)
        assertEquals("?", buttons[2].text)

        assertIs<UnknownAttachment>(unknown)
        assertEquals("hologram", unknown.type)
        assertEquals(mapOf("depth" to 3), unknown.properties["payload"])

        assertEquals(StrongMarkup(0, 5), body.markup!![0])
        val sparkle = assertIs<UnknownMarkup>(body.markup[1])
        assertEquals("sparkle", sparkle.type)
        assertEquals(1, sparkle.from)
        assertEquals("gold", sparkle.properties["color"])
    }

    @Test
    fun `unknown update type falls back to UnknownUpdate`() {
        val update = MaxJson.parseUpdate("""{"update_type":"chat_exploded","timestamp":5,"chat_id":42,"reason":"x"}""")
        assertIs<UnknownUpdate>(update)
        assertEquals("chat_exploded", update.updateType)
        assertEquals(5L, update.timestamp)
        assertEquals(42L, update.chatId)
        assertEquals("x", update.properties["reason"])
    }

    @Test
    fun `parses update list with callback`() {
        val list = mapper.readValue(
            """
            {"marker": 99, "updates": [{
              "update_type": "message_callback", "timestamp": 1,
              "callback": {"timestamp": 1, "callback_id": "cb1", "payload": "like",
                           "user": {"user_id": 3, "first_name": "Bob"}}
            }]}
            """,
            UpdateList::class.java,
        )
        assertEquals(99L, list.marker)
        val callback = assertIs<MessageCallbackUpdate>(list.updates.single())
        assertEquals("cb1", callback.callback.callbackId)
        assertEquals(3L, callback.user.userId)
        assertNull(callback.message)
    }

    @Test
    fun `unknown enum values map to UNKNOWN`() {
        val chat = mapper.readValue(
            """{"chat_id": 1, "type": "supergroup", "status": "frozen", "is_public": true, "participants_count": 3}""",
            Chat::class.java,
        )
        assertEquals(ChatType.UNKNOWN, chat.type)
        assertEquals(ChatStatus.UNKNOWN, chat.status)
        assertTrue(chat.isPublic)
        assertEquals(3, chat.participantsCount)
    }

    @Test
    fun `serializes new message with keyboard like the spec example and omits nulls`() {
        val body = message {
            text("It is message with inline keyboard")
            keyboard { row { callback("Press me!", "button1 pressed") } }
        }
        val json = mapper.readTree(mapper.writeValueAsString(body))
        val expected = mapper.readTree(
            """
            {
              "text": "It is message with inline keyboard",
              "attachments": [{
                "type": "inline_keyboard",
                "payload": { "buttons": [[ { "type": "callback", "text": "Press me!", "payload": "button1 pressed" } ]] }
              }]
            }
            """,
        )
        assertEquals(expected, json)
    }

    @Test
    fun `edit body without attachments omits the field, cleared attachments send empty list`() {
        assertFalse(mapper.writeValueAsString(NewMessageBody(text = "x")).contains("attachments"))
        val cleared = NewMessageBody.builder().text("x").clearAttachments().build()
        assertEquals("""{"text":"x","attachments":[]}""", mapper.writeValueAsString(cleared))
    }

    @Test
    fun `uses snake_case for boolean and numeric property names`() {
        val json = mapper.writeValueAsString(User(userId = 1, firstName = "A", isBot = true))
        assertEquals("""{"user_id":1,"first_name":"A","is_bot":true}""", json)
        val urls = mapper.readValue("""{"mp4_1080":"a","hls":"b"}""", VideoUrls::class.java)
        assertEquals("a", urls.mp4p1080)
        assertEquals("""{"mp4_1080":"a","hls":"b"}""", mapper.writeValueAsString(urls))
    }

    @Test
    fun `updates round-trip without computed properties`() {
        val original = MaxJson.parseUpdate(
            """
            {"update_type":"message_created","timestamp":1,
             "message":{"recipient":{"chat_id":5,"chat_type":"chat"},"timestamp":1,"body":{"mid":"m","seq":1,"text":"t"}}}
            """,
        )
        val json = mapper.writeValueAsString(original)
        val tree = mapper.readTree(json)
        assertEquals("message_created", tree["update_type"].asText())
        assertFalse(tree.has("chat_id"), json)
        assertFalse(tree.has("user"), json)
        assertEquals(original, mapper.readValue(json, Update::class.java))

        val started = MaxJson.parseUpdate("""{"update_type":"bot_started","timestamp":1,"chat_id":7,"user":{"user_id":8}}""")
        val startedTree = mapper.readTree(mapper.writeValueAsString(started))
        assertEquals(7L, startedTree["chat_id"].asLong())
        assertEquals(8L, startedTree["user"]["user_id"].asLong())
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T = kotlin.test.assertNotNull(value)
}
