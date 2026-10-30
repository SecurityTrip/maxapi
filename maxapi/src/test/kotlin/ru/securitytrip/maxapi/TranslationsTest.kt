package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.Translations
import java.util.MissingResourceException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationsTest {
    private val texts = Translations.of(
        "ru",
        mapOf(
            "ru" to mapOf(
                "hello" to "Привет, {0}!",
                "only.ru" to "Только по-русски",
                "apples.one" to "{0} яблоко",
                "apples.few" to "{0} яблока",
                "apples.many" to "{0} яблок",
                "pears.other" to "{0} груш {1}",
                "named" to "{name} купил {count} {missing} {5}",
            ),
            "EN" to mapOf("hello" to "Hello, {0}!", "apples.one" to "{0} apple", "apples.other" to "{0} apples"),
            "pt_BR" to mapOf("hello" to "Olá, {0}!"),
            "pt" to mapOf("hello" to "Oi, {0}!", "bye" to "Tchau"),
        ),
    )

    private val api = FakeApi()

    @AfterTest
    fun tearDown() = api.close()

    @Test
    fun `texts are chosen by locale with a fallback`() {
        assertEquals("Hello, Ann!", texts.text("en", "hello", "Ann"))
        assertEquals("Hello, Ann!", texts.text("en-US", "hello", "Ann"))
        assertEquals("Olá, Ann!", texts.text("pt-br", "hello", "Ann"))
        assertEquals("Oi, Ann!", texts.text("pt-PT", "hello", "Ann"))
        assertEquals("Tchau", texts.text("pt_BR", "bye"))
        assertEquals("Привет, Ann!", texts.text("de", "hello", "Ann"))
        assertEquals("Привет, Ann!", texts.text(null, "hello", "Ann"))
        assertEquals("Только по-русски", texts.text("en", "only.ru"))
        assertEquals("missing.key", texts.text("en", "missing.key"))
        assertEquals("Hello, {0}!", texts.text("en", "hello"))
        assertTrue(texts.has("en", "only.ru"))
        assertFalse(texts.has("en", "missing.key"))
        assertEquals(setOf("ru", "en", "pt-br", "pt"), texts.languages)
        assertEquals("ru", texts.fallback)
        assertEquals("Translations(fallback=ru, languages=[ru, en, pt-br, pt])", texts.toString())
    }

    @Test
    fun `named placeholders`() {
        assertEquals("Ann купил 3 {missing} {5}", texts.text("ru", "named", mapOf("name" to "Ann", "count" to 3)))
    }

    @Test
    fun `plural forms follow the language of the text`() {
        val ru = listOf(0L, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111, -1).map { texts.plural("ru", "apples", it) }
        assertEquals(
            listOf("0 яблок", "1 яблоко", "2 яблока", "4 яблока", "5 яблок", "11 яблок", "12 яблок", "14 яблок",
                "21 яблоко", "22 яблока", "25 яблок", "101 яблоко", "111 яблок", "-1 яблоко"),
            ru,
        )
        assertEquals(listOf("1 apple", "2 apples", "0 apples"), listOf(1L, 2, 0).map { texts.plural("en", "apples", it) })
        // German falls back to Russian, with Russian forms.
        assertEquals("3 яблока", texts.plural("de", "apples", 3))
        // Missing forms fall back to "other", then to the key.
        assertEquals("1 груш x", texts.plural("ru", "pears", 1, "x"))
        assertEquals("2 груш y", texts.plural("en", "pears", 2, "y"))
        assertEquals("plums", texts.plural("ru", "plums", 2))
    }

    @Test
    fun `texts for the user of a context`(): Unit = runBlocking {
        val replies = CopyOnWriteArrayList<String>()
        MaxBot(api.client).use { bot ->
            bot.onMessage { replies += texts.text(this, "hello", "Ann"); replies += texts.plural(this, "apples", 2) }
            bot.processUpdate(FakeApi.message("hi").copy(userLocale = "en-GB"))
            bot.processUpdate(FakeApi.message("hi"))
        }
        assertEquals(listOf("Hello, Ann!", "2 apples", "Привет, Ann!", "2 яблока"), replies)
    }

    @Test
    fun `texts from resource bundles`() {
        val bundles = Translations.fromBundles("i18n/messages", "ru", "en", "de")
        assertEquals("Привет, Ann!", bundles.text("ru", "hello", "Ann"))
        assertEquals("Hello, Ann!", bundles.text("en", "hello", "Ann"))
        // No German file: the base bundle is used.
        assertEquals("Hi, Ann!", bundles.text("de", "hello", "Ann"))
        assertFailsWith<MissingResourceException> { Translations.fromBundles("i18n/missing", "ru") }
    }

    @Test
    fun `the fallback language needs texts`() {
        assertFailsWith<IllegalArgumentException> { Translations.of("de", mapOf("ru" to mapOf("a" to "b"))) }
    }
}
