package ru.securitytrip.maxapi

import ru.securitytrip.maxapi.dsl.Buttons
import ru.securitytrip.maxapi.dsl.KeyboardBuilder
import ru.securitytrip.maxapi.dsl.keyboard
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.LocationAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequest
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.keyboard.ClipboardButton
import ru.securitytrip.maxapi.model.keyboard.LinkButton
import ru.securitytrip.maxapi.model.keyboard.MessageButton
import ru.securitytrip.maxapi.model.keyboard.OpenAppButton
import ru.securitytrip.maxapi.model.keyboard.RequestContactButton
import ru.securitytrip.maxapi.model.keyboard.RequestGeoLocationButton
import ru.securitytrip.maxapi.model.markup.TextFormat
import ru.securitytrip.maxapi.model.message.MessageLinkType
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.message.NewMessageLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class BuildersTest {
    @Test
    fun `button factories create the right buttons`() {
        assertEquals(CallbackButton("A", "a"), Buttons.callback("A", "a"))
        assertEquals(LinkButton("A", "https://x"), Buttons.link("A", "https://x"))
        assertEquals(RequestContactButton("A"), Buttons.requestContact("A"))
        assertEquals(RequestGeoLocationButton("A", null), Buttons.requestGeoLocation("A"))
        assertEquals(RequestGeoLocationButton("A", true), Buttons.requestGeoLocation("A", quick = true))
        assertEquals(MessageButton("A"), Buttons.message("A"))
        assertEquals(OpenAppButton("A", "bot"), Buttons.openApp("A", "bot"))
        assertEquals(OpenAppButton("A", "bot", "p", 5), Buttons.openApp("A", "bot", "p", 5))
        assertEquals(ClipboardButton("A", "copy"), Buttons.clipboard("A", "copy"))
    }

    @Test
    fun `row DSL offers every button kind`() {
        val kb = keyboard {
            row {
                callback("1", "one")
                link("2", "https://x")
                requestContact("3")
            }
            row {
                requestGeoLocation("4", quick = true)
                message("5")
                openApp("6", "bot", payload = "p")
                clipboard("7", "copy")
                button(Buttons.callback("8", "eight"))
            }
        }
        val rows = kb.payload.buttons
        assertEquals(listOf(3, 5), rows.map { it.size })
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), rows.flatten().map { it.text })
        assertEquals(
            listOf("callback", "link", "request_contact", "request_geo_location", "message", "open_app", "clipboard", "callback"),
            rows.flatten().map { it.type },
        )
    }

    @Test
    fun `keyboard builder skips empty rows and rejects empty keyboards`() {
        val kb = KeyboardBuilder()
            .row()
            .row(emptyList())
            .row(listOf(Buttons.callback("A", "a")))
            .row(Buttons.callback("B", "b"), Buttons.callback("C", "c"))
            .build()
        assertEquals(listOf(listOf("A"), listOf("B", "C")), kb.payload.buttons.map { row -> row.map { it.text } })

        assertFailsWith<IllegalStateException> { KeyboardBuilder().build() }
        assertFailsWith<IllegalStateException> { keyboard { row { } } }
    }

    @Test
    fun `keyboard builder copies the rows`() {
        val row = mutableListOf(Buttons.callback("A", "a"))
        val builder = KeyboardBuilder().row(row)
        row += Buttons.callback("B", "b")
        assertEquals(1, builder.build().payload.buttons.single().size)
    }

    @Test
    fun `message builder sets every field`() {
        val body = message {
            markdown("**hi**")
            notify(false)
            replyTo("mid.1")
        }
        assertEquals(NewMessageBody("**hi**", null, NewMessageLink(MessageLinkType.REPLY, "mid.1"), false, TextFormat.MARKDOWN), body)

        val html = message { html("<b>hi</b>"); forward("mid.2") }
        assertEquals(TextFormat.HTML, html.format)
        assertEquals(NewMessageLink(MessageLinkType.FORWARD, "mid.2"), html.link)

        val plain = NewMessageBody.builder().text("x").format(TextFormat.HTML).format(null).build()
        assertEquals(NewMessageBody(text = "x"), plain)
        assertEquals(NewMessageBody(text = "y"), NewMessageBody.text("y"))
    }

    @Test
    fun `message without attachments or keyboard sends no attachments field`() {
        assertNull(message { text("x") }.attachments)
    }

    @Test
    fun `keyboard rows are merged into one keyboard placed after other attachments`() {
        val photo = PhotoAttachmentRequest.ofUrl("https://i")
        val location = LocationAttachmentRequest(1.0, 2.0)
        val body = NewMessageBody.builder()
            .keyboardRow(Buttons.callback("A", "a"))
            .attach(photo)
            .keyboard { row { callback("B", "b") } }
            .attach(location)
            .build()

        val attachments = body.attachments!!
        assertEquals(listOf(photo, location), attachments.take(2))
        val kb = assertIs<InlineKeyboardAttachmentRequest>(attachments.last())
        assertEquals(listOf(listOf("A"), listOf("B")), kb.payload.buttons.map { row -> row.map { it.text } })
        assertEquals(3, attachments.size)
    }

    @Test
    fun `clearAttachments sends an empty list but keeps a later keyboard`() {
        assertEquals(emptyList(), message { clearAttachments() }.attachments)

        val body = message {
            attach(PhotoAttachmentRequest.ofToken("t"))
            clearAttachments()
            keyboard { row { callback("A", "a") } }
        }
        assertIs<InlineKeyboardAttachmentRequest>(body.attachments!!.single())
    }

    @Test
    fun `built bodies are not affected by later builder changes`() {
        val builder = NewMessageBody.builder().attach(PhotoAttachmentRequest.ofToken("t"))
        val first = builder.build()
        builder.attach(PhotoAttachmentRequest.ofToken("u"))
        assertEquals(1, first.attachments!!.size)
    }
}
