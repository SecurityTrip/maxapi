package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.Confirmation
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.Paginator
import ru.securitytrip.maxapi.client.MaxLimits
import ru.securitytrip.maxapi.dsl.Buttons
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.keyboard.Button
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardsTest {
    private val api = FakeApi()
    private val bot = MaxBot(api.client)
    private val log = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        bot.close()
        api.close()
    }

    private fun List<List<Button>>.texts() = map { row -> row.map { it.text } }

    @Test
    fun `pages of a list with a page row`() {
        val paginator = Paginator("items", pageSize = 3, columns = 2)
        val items = (1..8).map { "item $it" }
        val button = { item: String -> Buttons.callback(item, "pick:$item") }

        assertEquals(3, paginator.pageCount(items.size))
        assertEquals(1, paginator.pageCount(0))
        assertEquals(listOf("item 7", "item 8"), paginator.items(items, 2))
        assertEquals(listOf("item 7", "item 8"), paginator.items(items, 99))
        assertEquals(listOf("item 1", "item 2", "item 3"), paginator.items(items, -1))

        assertEquals(
            listOf(listOf("item 1", "item 2"), listOf("item 3"), listOf("1/3", "▶")),
            paginator.rows(items, 0, button = button).texts(),
        )
        assertEquals(listOf("◀", "2/3", "▶"), paginator.rows(items, 1, button = button).last().map { it.text })
        assertEquals(listOf("◀", "3/3"), paginator.rows(items, 2, button = button).last().map { it.text })
        assertEquals(
            listOf("items:1:cat", "items:2:cat"),
            paginator.navigation(2, 3, "cat").map { (it as ru.securitytrip.maxapi.model.keyboard.CallbackButton).payload },
        )

        // One page: no page row; no items: no rows.
        assertEquals(listOf(listOf("a")), paginator.rows(listOf("a"), 0, button = button).texts())
        assertEquals(emptyList(), paginator.rows(emptyList(), 0, button = button))
        assertEquals(emptyList(), paginator.navigation(0, 1))
        assertFailsWith<IllegalArgumentException> { paginator.keyboard(emptyList<String>(), 0, button = button) }
        assertEquals(3, paginator.keyboard(items, 0, button = button).payload.buttons.size)
    }

    @Test
    fun `rows with wide buttons hold at most three`() {
        val paginator = Paginator("links", pageSize = 8, columns = 5)
        val rows = paginator.rows((1..8).toList(), 0) { if (it in 2..4) Buttons.link("L$it", "https://x/$it") else Buttons.callback("C$it", "c") }
        assertEquals(listOf(listOf("C1", "L2", "L3"), listOf("L4", "C5", "C6"), listOf("C7", "C8")), rows.texts())
        rows.forEach { row -> assertTrue(row.size <= MaxLimits.BUTTONS_PER_ROW) }
    }

    @Test
    fun `paginators keep to the limits of Max`() {
        assertFailsWith<IllegalArgumentException> { Paginator("p", pageSize = 0) }
        assertFailsWith<IllegalArgumentException> { Paginator("p", columns = 0) }
        assertFailsWith<IllegalArgumentException> { Paginator("p", columns = 8) }
        assertFailsWith<IllegalArgumentException> { Paginator("p", pageSize = 30, columns = 1) }
        assertFailsWith<IllegalArgumentException> { Paginator("p", pageSize = 208, columns = 7) }
        assertFailsWith<IllegalArgumentException> { Paginator("p:x") }
        Paginator("p", pageSize = 29, columns = 1)
        val wide = Paginator("p", pageSize = 203, columns = 7)
        assertEquals(29, wide.rows((1..203).toList(), 0) { Buttons.callback("$it", "x") }.size)
        // Links fit only three in a row, so the same page no longer fits.
        assertFailsWith<IllegalArgumentException> { wide.rows((1..203).toList(), 0) { Buttons.link("$it", "https://x") } }
        val defaults = Paginator("p")
        assertEquals(10, defaults.pageSize)
        assertEquals(1, defaults.columns)
        assertEquals("◀", defaults.previousText)
        assertEquals("▶", defaults.nextText)
    }

    @Test
    fun `page presses are routed and read`(): Unit = runBlocking {
        val paginator = Paginator("catalog", pageSize = 2)
        bot.onCallback(paginator.filter()) { log += "page ${paginator.page(this)} arg '${paginator.arg(this)}'" }
        bot.processUpdate(FakeApi.press("catalog:3:shoes"))
        bot.processUpdate(FakeApi.press("catalog:x:"))
        bot.processUpdate(FakeApi.press("other:1:"))
        assertEquals(listOf("page 3 arg 'shoes'", "page 0 arg ''"), log)
    }

    @Test
    fun `confirmation buttons and filters`(): Unit = runBlocking {
        val delete = Confirmation("delete")
        assertEquals(listOf("delete:yes:42", "delete:no:42"), delete.buttons("42").map { (it as ru.securitytrip.maxapi.model.keyboard.CallbackButton).payload })
        assertEquals(listOf(listOf("Да", "Нет")), delete.keyboard("42", "Да", "Нет").payload.buttons.texts())
        assertEquals(listOf(listOf("Yes", "No")), delete.keyboard().payload.buttons.texts())

        bot.onCallback(delete.yes()) { log += "yes ${delete.arg(this)} ${delete.isYes(this)}" }
        bot.onCallback(delete.no()) { log += "no ${delete.arg(this)} ${delete.isYes(this)}" }
        bot.onCallback(delete.filter()) { log += "either" }
        bot.onCallback { log += "other" }
        bot.processUpdate(FakeApi.press("delete:yes:42"))
        bot.processUpdate(FakeApi.press("delete:no:7"))
        bot.processUpdate(FakeApi.press("delete:maybe:7"))
        bot.processUpdate(FakeApi.press("keep:yes:1"))
        assertEquals(listOf("yes 42 true", "no 7 false", "either", "other"), log)
        assertFalse(delete.callbackData.matches("x"))
    }

    @Test
    fun `keyboards fit messages`(): Unit = runBlocking {
        val paginator = Paginator("p", pageSize = 5, columns = 3)
        api.client.sendMessageToChat(1, message { text("List"); attach(paginator.keyboard((1..20).toList(), 1) { Buttons.callback("$it", "n:$it") }) })
        api.client.sendMessageToChat(1, message { text("Sure?"); keyboard { row(Confirmation("c").buttons()) } })
        assertEquals(2, api.sent.size)
    }
}
