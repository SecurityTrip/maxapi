package ru.securitytrip.maxapi

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import ru.securitytrip.maxapi.client.MaxException
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.model.ApiEnum
import ru.securitytrip.maxapi.model.attachment.Attachment
import ru.securitytrip.maxapi.model.attachment.AttachmentRequest
import ru.securitytrip.maxapi.model.attachment.AudioAttachment
import ru.securitytrip.maxapi.model.attachment.AudioAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.ContactAttachment
import ru.securitytrip.maxapi.model.attachment.ContactAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.ContactAttachmentRequestPayload
import ru.securitytrip.maxapi.model.attachment.FileAttachment
import ru.securitytrip.maxapi.model.attachment.FileAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachment
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.LocationAttachment
import ru.securitytrip.maxapi.model.attachment.LocationAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.PhotoAttachment
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequestPayload
import ru.securitytrip.maxapi.model.attachment.PhotoToken
import ru.securitytrip.maxapi.model.attachment.ShareAttachment
import ru.securitytrip.maxapi.model.attachment.ShareAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.StickerAttachment
import ru.securitytrip.maxapi.model.attachment.StickerAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.UnknownAttachment
import ru.securitytrip.maxapi.model.attachment.UploadType
import ru.securitytrip.maxapi.model.attachment.VideoAttachment
import ru.securitytrip.maxapi.model.attachment.VideoAttachmentRequest
import ru.securitytrip.maxapi.model.chat.Chat
import ru.securitytrip.maxapi.model.chat.ChatAdminPermission
import ru.securitytrip.maxapi.model.chat.ChatMember
import ru.securitytrip.maxapi.model.chat.ChatStatus
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.keyboard.Button
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.keyboard.ClipboardButton
import ru.securitytrip.maxapi.model.keyboard.LinkButton
import ru.securitytrip.maxapi.model.keyboard.MessageButton
import ru.securitytrip.maxapi.model.keyboard.OpenAppButton
import ru.securitytrip.maxapi.model.keyboard.RequestContactButton
import ru.securitytrip.maxapi.model.keyboard.RequestGeoLocationButton
import ru.securitytrip.maxapi.model.keyboard.UnknownButton
import ru.securitytrip.maxapi.model.markup.EmphasizedMarkup
import ru.securitytrip.maxapi.model.markup.HeadingMarkup
import ru.securitytrip.maxapi.model.markup.HighlightedMarkup
import ru.securitytrip.maxapi.model.markup.LinkMarkup
import ru.securitytrip.maxapi.model.markup.MarkupElement
import ru.securitytrip.maxapi.model.markup.MonospacedMarkup
import ru.securitytrip.maxapi.model.markup.QuoteMarkup
import ru.securitytrip.maxapi.model.markup.StrikethroughMarkup
import ru.securitytrip.maxapi.model.markup.StrongMarkup
import ru.securitytrip.maxapi.model.markup.TextFormat
import ru.securitytrip.maxapi.model.markup.UnderlineMarkup
import ru.securitytrip.maxapi.model.markup.UnknownMarkup
import ru.securitytrip.maxapi.model.markup.UserMentionMarkup
import ru.securitytrip.maxapi.model.message.CommentMessage
import ru.securitytrip.maxapi.model.message.MessageLinkType
import ru.securitytrip.maxapi.model.update.BotAddedToChatUpdate
import ru.securitytrip.maxapi.model.update.BotAdminPermissionsChangedUpdate
import ru.securitytrip.maxapi.model.update.BotRemovedFromChatUpdate
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.BotStoppedUpdate
import ru.securitytrip.maxapi.model.update.ChatTitleChangedUpdate
import ru.securitytrip.maxapi.model.update.CommentCreatedUpdate
import ru.securitytrip.maxapi.model.update.CommentEditedUpdate
import ru.securitytrip.maxapi.model.update.CommentRemovedUpdate
import ru.securitytrip.maxapi.model.update.DialogClearedUpdate
import ru.securitytrip.maxapi.model.update.DialogMutedUpdate
import ru.securitytrip.maxapi.model.update.DialogRemovedUpdate
import ru.securitytrip.maxapi.model.update.DialogUnmutedUpdate
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.MessageEditedUpdate
import ru.securitytrip.maxapi.model.update.MessageRemovedUpdate
import ru.securitytrip.maxapi.model.update.UnknownUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.update.UserAddedToChatUpdate
import ru.securitytrip.maxapi.model.update.UserRemovedFromChatUpdate
import ru.securitytrip.maxapi.model.update.updateTypeOf
import ru.securitytrip.maxapi.model.user.BotInfo
import ru.securitytrip.maxapi.model.user.UserWithPhoto
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {
    private val mapper = MaxJson.createMapper()

    private fun tree(json: String): JsonNode = mapper.readTree(json)

    /** Parses [json] as [base], checks the concrete class, and checks that writing it back gives the same JSON. */
    private fun <B : Any> roundTrip(json: String, base: Class<B>, expected: KClass<out B>): B {
        val value = mapper.readValue(json, base)
        assertEquals(expected, value::class)
        assertEquals(tree(json), tree(mapper.writeValueAsString(value)), "serialized form of ${expected.simpleName}")
        assertEquals(value, mapper.readValue(mapper.writeValueAsString(value), base))
        return value
    }

    // ------------------------------------------------------------------ updates

    private val user = """{"user_id":7,"first_name":"Ann","is_bot":false}"""
    private val message =
        """{"sender":$user,"recipient":{"chat_id":42,"chat_type":"dialog"},"timestamp":1,"body":{"mid":"mid.1","seq":1,"text":"hi"}}"""

    /** One sample of every update type: JSON, class, expected chatId and user id. */
    private val updates = listOf(
        Triple("""{"update_type":"message_created","timestamp":1,"message":$message,"user_locale":"ru"}""", MessageCreatedUpdate::class, 42L to 7L),
        Triple(
            """{"update_type":"message_callback","timestamp":1,"callback":{"timestamp":1,"callback_id":"cb","payload":"p","user":$user},"message":$message,"user_locale":"en"}""",
            MessageCallbackUpdate::class, 42L to 7L,
        ),
        Triple("""{"update_type":"message_edited","timestamp":1,"message":$message}""", MessageEditedUpdate::class, 42L to 7L),
        Triple("""{"update_type":"message_removed","timestamp":1,"message_id":"mid.1","chat_id":42,"user_id":7}""", MessageRemovedUpdate::class, 42L to null),
        Triple("""{"update_type":"comment_created","timestamp":1,"message":$message}""", CommentCreatedUpdate::class, 42L to 7L),
        Triple("""{"update_type":"comment_edited","timestamp":1,"message":$message}""", CommentEditedUpdate::class, 42L to 7L),
        Triple(
            """{"update_type":"comment_removed","timestamp":1,"message_id":"c1","chat_id":42,"user_id":7,"post_id":"mid.1"}""",
            CommentRemovedUpdate::class, 42L to null,
        ),
        Triple("""{"update_type":"bot_added","timestamp":1,"chat_id":42,"user":$user,"is_channel":true}""", BotAddedToChatUpdate::class, 42L to 7L),
        Triple("""{"update_type":"bot_removed","timestamp":1,"chat_id":42,"user":$user,"is_channel":false}""", BotRemovedFromChatUpdate::class, 42L to 7L),
        Triple(
            """{"update_type":"user_added","timestamp":1,"chat_id":42,"user":$user,"inviter_id":9,"is_channel":false}""",
            UserAddedToChatUpdate::class, 42L to 7L,
        ),
        Triple(
            """{"update_type":"user_removed","timestamp":1,"chat_id":42,"user":$user,"admin_id":9,"is_channel":false}""",
            UserRemovedFromChatUpdate::class, 42L to 7L,
        ),
        Triple(
            """{"update_type":"bot_started","timestamp":1,"chat_id":42,"user":$user,"payload":"ref","user_locale":"ru"}""",
            BotStartedUpdate::class, 42L to 7L,
        ),
        Triple("""{"update_type":"bot_stopped","timestamp":1,"chat_id":42,"user":$user,"user_locale":"ru"}""", BotStoppedUpdate::class, 42L to 7L),
        Triple("""{"update_type":"dialog_cleared","timestamp":1,"chat_id":42,"user":$user,"user_locale":"ru"}""", DialogClearedUpdate::class, 42L to 7L),
        Triple("""{"update_type":"dialog_removed","timestamp":1,"chat_id":42,"user":$user,"user_locale":"ru"}""", DialogRemovedUpdate::class, 42L to 7L),
        Triple(
            """{"update_type":"dialog_muted","timestamp":1,"chat_id":42,"user":$user,"muted_until":99,"user_locale":"ru"}""",
            DialogMutedUpdate::class, 42L to 7L,
        ),
        Triple("""{"update_type":"dialog_unmuted","timestamp":1,"chat_id":42,"user":$user,"user_locale":"ru"}""", DialogUnmutedUpdate::class, 42L to 7L),
        Triple("""{"update_type":"chat_title_changed","timestamp":1,"chat_id":42,"user":$user,"title":"New"}""", ChatTitleChangedUpdate::class, 42L to 7L),
        Triple(
            """{"update_type":"bot_admin_permissions_changed","timestamp":1,"chat_id":42,"user_id":7,"bot_id":8,"is_channel":false,"is_admin":true,"permissions":["read_all_messages","write"]}""",
            BotAdminPermissionsChangedUpdate::class, 42L to null,
        ),
        Triple("""{"update_type":"something_new","timestamp":1,"chat_id":42,"extra":{"a":1}}""", UnknownUpdate::class, 42L to null),
    )

    @TestFactory
    fun `every update type parses and round-trips`() = updates.map { (json, type, ids) ->
        DynamicTest.dynamicTest(type.simpleName!!) {
            val update = roundTrip(json, Update::class.java, type)
            assertEquals(tree(json)["update_type"].asText(), update.updateType)
            assertEquals(1L, update.timestamp)
            assertEquals(ids.first, update.chatId, "chatId")
            assertEquals(ids.second, update.user?.userId, "user")
            if (update !is UnknownUpdate) {
                assertEquals(update.updateType, updateTypeOf(update.javaClass)?.value, "updateTypeOf")
            }
        }
    }

    @Test
    fun `samples cover every update type`() {
        val sampled = updates.map { tree(it.first)["update_type"].asText() }.toSet()
        assertEquals(UpdateType.entries.map { it.value }.toSet(), sampled - "something_new")
        assertNull(updateTypeOf(Update::class.java))
        assertNull(updateTypeOf(UnknownUpdate::class.java))
    }

    @Test
    fun `update specific fields`() {
        val muted = MaxJson.parseUpdate(updates.first { it.second == DialogMutedUpdate::class }.first) as DialogMutedUpdate
        assertEquals(99L, muted.mutedUntil)
        val permissions = MaxJson.parseUpdate(updates.first { it.second == BotAdminPermissionsChangedUpdate::class }.first)
            as BotAdminPermissionsChangedUpdate
        assertEquals(setOf(ChatAdminPermission.READ_ALL_MESSAGES, ChatAdminPermission.WRITE), permissions.permissions)
        assertEquals(8L, permissions.botId)
        val unknown = MaxJson.parseUpdate(updates.last().first) as UnknownUpdate
        assertEquals(mapOf("a" to 1), unknown.properties["extra"])
        assertTrue(unknown.toString().contains("something_new"))
        assertEquals(unknown.hashCode(), (MaxJson.parseUpdate(updates.last().first) as UnknownUpdate).hashCode())
    }

    @Test
    fun `parseUpdate accepts bytes and rejects garbage`() {
        assertIs<BotStartedUpdate>(MaxJson.parseUpdate(updates[11].first.toByteArray()))
        assertFailsWith<MaxException> { MaxJson.parseUpdate("{") }
        assertFailsWith<MaxException> { MaxJson.parseUpdate("[]".toByteArray()) }
        assertNotSame(MaxJson.createMapper(), MaxJson.createMapper())
    }

    // -------------------------------------------------------------- attachments

    private val attachments = listOf(
        """{"type":"image","payload":{"photo_id":1,"token":"t","url":"https://i"}}""" to PhotoAttachment::class,
        """{"type":"video","payload":{"url":"https://v","token":"t"},"thumbnail":{"url":"https://th"},"width":640,"height":360,"duration":5}""" to VideoAttachment::class,
        """{"type":"audio","payload":{"url":"https://a","token":"t"},"transcription":"hello"}""" to AudioAttachment::class,
        """{"type":"file","payload":{"url":"https://f","token":"t"},"filename":"a.pdf","size":10}""" to FileAttachment::class,
        """{"type":"sticker","payload":{"url":"https://s","code":"c"},"width":128,"height":128}""" to StickerAttachment::class,
        """{"type":"contact","payload":{"vcf_info":"BEGIN:VCARD","hash":"h","max_info":{"user_id":3,"first_name":"Bob","is_bot":false}}}""" to ContactAttachment::class,
        """{"type":"inline_keyboard","payload":{"buttons":[[{"type":"callback","text":"A","payload":"a"}]]}}""" to InlineKeyboardAttachment::class,
        """{"type":"share","payload":{"url":"https://x","token":"t"},"title":"T","description":"D","image_url":"https://img"}""" to ShareAttachment::class,
        """{"type":"location","latitude":55.75,"longitude":37.62}""" to LocationAttachment::class,
        """{"type":"hologram","payload":{"depth":3}}""" to UnknownAttachment::class,
    )

    @TestFactory
    fun `every attachment type parses and round-trips`() = attachments.map { (json, type) ->
        DynamicTest.dynamicTest(type.simpleName!!) {
            val attachment = roundTrip(json, Attachment::class.java, type)
            assertEquals(tree(json)["type"].asText(), attachment.type)
        }
    }

    private val attachmentRequests = listOf(
        PhotoAttachmentRequest(PhotoAttachmentRequestPayload(photos = mapOf("k" to PhotoToken("t")))) to
            """{"type":"image","payload":{"photos":{"k":{"token":"t"}}}}""",
        PhotoAttachmentRequest.ofUrl("https://i") to """{"type":"image","payload":{"url":"https://i"}}""",
        PhotoAttachmentRequest.ofToken("t") to """{"type":"image","payload":{"token":"t"}}""",
        VideoAttachmentRequest.ofToken("v") to """{"type":"video","payload":{"token":"v"}}""",
        AudioAttachmentRequest.ofToken("a") to """{"type":"audio","payload":{"token":"a"}}""",
        FileAttachmentRequest.ofToken("f") to """{"type":"file","payload":{"token":"f"}}""",
        StickerAttachmentRequest.ofCode("s") to """{"type":"sticker","payload":{"code":"s"}}""",
        ContactAttachmentRequest(ContactAttachmentRequestPayload(name = "Bob", contactId = 3, vcfPhone = "+7")) to
            """{"type":"contact","payload":{"name":"Bob","contact_id":3,"vcf_phone":"+7"}}""",
        InlineKeyboardAttachmentRequest(listOf(listOf(CallbackButton("A", "a")))) to
            """{"type":"inline_keyboard","payload":{"buttons":[[{"type":"callback","text":"A","payload":"a"}]]}}""",
        LocationAttachmentRequest(1.5, 2.5) to """{"type":"location","latitude":1.5,"longitude":2.5}""",
        ShareAttachmentRequest.ofUrl("https://x") to """{"type":"share","payload":{"url":"https://x"}}""",
    )

    @TestFactory
    fun `every attachment request serializes to the API format`() = attachmentRequests.map { (request, json) ->
        DynamicTest.dynamicTest(request::class.simpleName + " " + json) {
            assertEquals(tree(json), tree(mapper.writeValueAsString(request)))
            assertEquals(request, mapper.readValue(json, AttachmentRequest::class.java))
        }
    }

    // ------------------------------------------------------------ buttons, markup

    private val buttons = listOf(
        """{"type":"callback","text":"A","payload":"a"}""" to CallbackButton::class,
        """{"type":"link","text":"A","url":"https://x"}""" to LinkButton::class,
        """{"type":"request_geo_location","text":"A","quick":true}""" to RequestGeoLocationButton::class,
        """{"type":"request_contact","text":"A"}""" to RequestContactButton::class,
        """{"type":"message","text":"A"}""" to MessageButton::class,
        """{"type":"open_app","text":"A","web_app":"bot","payload":"p","contact_id":5}""" to OpenAppButton::class,
        """{"type":"clipboard","text":"A","payload":"copy me"}""" to ClipboardButton::class,
        """{"type":"teleport","text":"A","destination":"moon"}""" to UnknownButton::class,
    )

    @TestFactory
    fun `every button type parses and round-trips`() = buttons.map { (json, type) ->
        DynamicTest.dynamicTest(type.simpleName!!) {
            val button = roundTrip(json, Button::class.java, type)
            assertEquals(tree(json)["type"].asText(), button.type)
            assertEquals("A", button.text)
        }
    }

    private val markup = listOf(
        """{"type":"strong","from":0,"length":1}""" to StrongMarkup::class,
        """{"type":"emphasized","from":0,"length":1}""" to EmphasizedMarkup::class,
        """{"type":"monospaced","from":0,"length":1}""" to MonospacedMarkup::class,
        """{"type":"link","from":0,"length":1,"url":"https://x"}""" to LinkMarkup::class,
        """{"type":"strikethrough","from":0,"length":1}""" to StrikethroughMarkup::class,
        """{"type":"underline","from":0,"length":1}""" to UnderlineMarkup::class,
        """{"type":"user_mention","from":0,"length":1,"user_link":"@ann","user_id":7}""" to UserMentionMarkup::class,
        """{"type":"heading","from":0,"length":1}""" to HeadingMarkup::class,
        """{"type":"highlighted","from":0,"length":1}""" to HighlightedMarkup::class,
        """{"type":"quote","from":0,"length":1}""" to QuoteMarkup::class,
        """{"type":"sparkle","from":0,"length":1,"color":"gold"}""" to UnknownMarkup::class,
    )

    @TestFactory
    fun `every markup type parses and round-trips`() = markup.map { (json, type) ->
        DynamicTest.dynamicTest(type.simpleName!!) {
            val element = roundTrip(json, MarkupElement::class.java, type)
            assertEquals(tree(json)["type"].asText(), element.type)
            assertEquals(0, element.from)
            assertEquals(1, element.length)
        }
    }

    @Test
    fun `unknown types compare by content`() {
        val a = mapper.readValue(buttons.last().first, Button::class.java) as UnknownButton
        val b = mapper.readValue(buttons.last().first, Button::class.java) as UnknownButton
        val other = mapper.readValue("""{"type":"teleport","text":"A","destination":"mars"}""", Button::class.java)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals<Button>(a, other)
        assertEquals("moon", a.properties["destination"])
        assertTrue(a.toString().contains("teleport"))

        val m1 = mapper.readValue(markup.last().first, MarkupElement::class.java)
        val m2 = mapper.readValue(markup.last().first, MarkupElement::class.java)
        assertEquals(m1, m2)
        assertEquals(m1.hashCode(), m2.hashCode())
        assertTrue(m1.toString().contains("sparkle"))

        val x1 = mapper.readValue(attachments.last().first, Attachment::class.java)
        val x2 = mapper.readValue(attachments.last().first, Attachment::class.java)
        assertEquals(x1, x2)
        assertEquals(x1.hashCode(), x2.hashCode())
        assertTrue(x1.toString().contains("hologram"))
    }

    @Test
    fun `unknown types tolerate a missing discriminator`() {
        val attachment = mapper.readValue("""{"payload":{}}""", Attachment::class.java)
        assertIs<UnknownAttachment>(attachment)
        assertEquals("", attachment.type)
        val update = MaxJson.parseUpdate("""{"chat_id":1}""")
        assertIs<UnknownUpdate>(update)
        assertEquals("", update.updateType)
    }

    // -------------------------------------------------------------------- enums

    private val enums: List<Array<out ApiEnum>> = listOf(
        ChatType.entries.toTypedArray(),
        ChatStatus.entries.toTypedArray(),
        ChatAdminPermission.entries.toTypedArray(),
        SenderAction.entries.toTypedArray(),
        UploadType.entries.toTypedArray(),
        TextFormat.entries.toTypedArray(),
        MessageLinkType.entries.toTypedArray(),
        UpdateType.entries.toTypedArray(),
    )

    @TestFactory
    fun `every enum constant uses its wire value`() = enums.flatMap { it.asList() }.map { constant ->
        DynamicTest.dynamicTest("${constant.javaClass.simpleName}.$constant") {
            assertEquals("\"${constant.value}\"", mapper.writeValueAsString(constant))
            assertEquals(constant, mapper.readValue("\"${constant.value}\"", constant.javaClass))
        }
    }

    @Test
    fun `extensible enums fall back to UNKNOWN`() {
        assertEquals(ChatType.UNKNOWN, mapper.readValue("\"supergroup\"", ChatType::class.java))
        assertEquals(ChatStatus.UNKNOWN, mapper.readValue("\"frozen\"", ChatStatus::class.java))
        assertEquals(ChatAdminPermission.UNKNOWN, mapper.readValue("\"fly\"", ChatAdminPermission::class.java))
        assertEquals(MessageLinkType.UNKNOWN, mapper.readValue("\"quote\"", MessageLinkType::class.java))
    }

    // ------------------------------------------------------------- other models

    @Test
    fun `parses a full chat`() {
        val chat = mapper.readValue(
            """
            {"chat_id":-5,"type":"dialog","status":"suspended","title":null,"icon":{"url":"https://icon"},
             "last_event_time":10,"participants_count":2,"owner_id":1,"participants":{"1":100,"2":200},
             "is_public":false,"link":"https://max.ru/join/x","description":"d",
             "dialog_with_user":{"user_id":2,"first_name":"Ann","description":"hi","avatar_url":"a","full_avatar_url":"fa"},
             "messages_count":12,"pinned_message":${messageJson()}}
            """,
            Chat::class.java,
        )
        assertEquals(ChatType.DIALOG, chat.type)
        assertEquals(ChatStatus.SUSPENDED, chat.status)
        assertEquals("https://icon", chat.icon?.url)
        assertEquals(mapOf("1" to 100L, "2" to 200L), chat.participants)
        assertEquals("fa", chat.dialogWithUser?.fullAvatarUrl)
        assertEquals("mid.1", chat.pinnedMessage?.body?.mid)
        assertEquals(12, chat.messagesCount)
    }

    @Test
    fun `parses users, members and comments`() {
        val member = mapper.readValue(
            """{"user_id":2,"first_name":"Ann","last_name":"Lee","username":"ann","is_bot":false,"last_activity_time":5,
                "last_access_time":6,"is_owner":true,"is_admin":true,"join_time":1,"permissions":["write","unknown_one"],"alias":"Boss"}""",
            ChatMember::class.java,
        )
        assertTrue(member.isOwner)
        assertEquals(setOf(ChatAdminPermission.WRITE, ChatAdminPermission.UNKNOWN), member.permissions)
        assertEquals("Lee", member.lastName)
        assertEquals(5L, member.lastActivityTime)

        val bot = mapper.readValue("""{"user_id":1,"first_name":"Bot","commands":[{"name":"start"}]}""", BotInfo::class.java)
        assertTrue(bot.isBot) // bots default to true
        val withPhoto = mapper.readValue("""{"user_id":3,"avatar_url":"a"}""", UserWithPhoto::class.java)
        assertEquals("", withPhoto.firstName)

        val comment = mapper.readValue(
            """{"sender":$user,"recipient":{"chat_id":-5,"chat_type":"channel","post_id":"mid.1"},"timestamp":1,
                "link":{"type":"reply","chat_id":-5,"message":{"mid":"c0","seq":1}},
                "body":{"mid":"c1","seq":2,"text":"t","markup":[{"type":"strong","from":0,"length":1}]},"stat":{"views":4}}""",
            CommentMessage::class.java,
        )
        assertEquals("mid.1", comment.recipient.postId)
        assertEquals(MessageLinkType.REPLY, comment.link?.type)
        assertEquals(4, comment.stat?.views)
    }

    @Test
    fun `parses forwarded and replied messages`() {
        val forwarded = mapper.readValue(
            """{"recipient":{"chat_id":1,"chat_type":"chat"},"timestamp":1,
                "link":{"type":"forward","sender":$user,"chat_id":-9,"message":{"mid":"orig","seq":3,"text":"original"}},
                "url":"https://max.ru/c/1/mid","stat":{"views":10}}""",
            ru.securitytrip.maxapi.model.message.Message::class.java,
        )
        val link = forwarded.link!!
        assertEquals(MessageLinkType.FORWARD, link.type)
        assertEquals(-9L, link.chatId)
        assertEquals(7L, link.sender?.userId)
        assertEquals("original", link.message.text)
        assertNull(forwarded.body) // a pure forward has no body of its own
        assertEquals(10, forwarded.stat?.views)
        assertEquals("https://max.ru/c/1/mid", forwarded.url)
    }

    @Test
    fun `nulls in the JSON fall back to defaults`() {
        val chat = mapper.readValue("""{"chat_id":1,"type":null,"is_public":null}""", Chat::class.java)
        assertEquals(ChatType.UNKNOWN, chat.type)
        assertEquals(false, chat.isPublic)
    }
}
