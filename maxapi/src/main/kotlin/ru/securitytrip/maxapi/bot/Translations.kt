package ru.securitytrip.maxapi.bot

import java.util.Locale
import java.util.ResourceBundle
import kotlin.math.abs

/**
 * Texts of a bot in several languages, chosen by the locale of the user ([UpdateContext.locale]), with
 * a fallback language for users whose language the bot does not speak.
 *
 * ```kotlin
 * val texts = Translations.of(
 *     fallback = "ru",
 *     texts = mapOf(
 *         "ru" to mapOf("hello" to "Привет, {0}!", "apples.one" to "{0} яблоко", "apples.few" to "{0} яблока", "apples.many" to "{0} яблок"),
 *         "en" to mapOf("hello" to "Hello, {0}!", "apples.one" to "{0} apple", "apples.other" to "{0} apples"),
 *     ),
 * )
 * bot.onCommand("start") { reply(texts.text(this, "hello", user?.firstName)) }
 * texts.plural("ru", "apples", 5)     // "5 яблок"
 * ```
 *
 * From `messages_ru.properties`, `messages_en.properties` (UTF-8) on the classpath:
 *
 * ```java
 * Translations texts = Translations.fromBundles("messages", "ru", "en");
 * ctx.replyBlocking(texts.text(ctx, "hello", ctx.getUser().getFirstName()));
 * ```
 *
 * A text is looked up for the full locale (`pt-BR`), then its language (`pt`), then the [fallback]
 * language; a key found nowhere is returned as it is, so a missing text shows up instead of failing.
 * Locales are compared ignoring case and `_` versus `-`.
 *
 * Placeholders `{0}`, `{1}`, ... are replaced with the arguments, `{name}` with the values of a map;
 * other braces stay as they are.
 *
 * @property fallback the language used when the user's one has no text.
 */
public class Translations private constructor(
    public val fallback: String,
    texts: Map<String, Map<String, String>>,
) {
    private val texts: Map<String, Map<String, String>> = texts.mapKeys { normalize(it.key) }

    /** The languages that have texts, normalized: lowercase, with `-`. */
    public val languages: Set<String> get() = texts.keys

    /** The text of [key] for [locale], with `{0}`, `{1}`, ... replaced by [args]. */
    public fun text(locale: String?, key: String, vararg args: Any?): String =
        format(lookup(locale) { it.map[key] } ?: key, args.asList(), emptyMap())

    /** The text of [key] for [locale], with `{name}` placeholders replaced by the values of [args]. */
    public fun text(locale: String?, key: String, args: Map<String, Any?>): String =
        format(lookup(locale) { it.map[key] } ?: key, emptyList(), args)

    /** The text of [key] for the locale of the user of [ctx]; see [text]. */
    public fun text(ctx: UpdateContext<*>, key: String, vararg args: Any?): String = text(ctx.locale, key, *args)

    /**
     * The text of [key] for a number: the key `<key>.<form>` with the form of [count] in the language of
     * the text, `{0}` replaced by [count] and `{1}`, `{2}`, ... by [args].
     *
     * Russian, Ukrainian and Belarusian have the forms `one` (1, 21), `few` (2–4, 22) and `many` (5–20,
     * 25, 11); other languages `one` (1) and `other`. A missing form falls back to `<key>.other`, and
     * `few` to `many` before that.
     */
    public fun plural(locale: String?, key: String, count: Long, vararg args: Any?): String {
        val text = lookup(locale) { texts ->
            pluralForms(texts.language, count).firstNotNullOfOrNull { texts.map["$key.$it"] }
        }
        return format(text ?: key, listOf(count) + args, emptyMap())
    }

    /** The text of [key] for a number, for the locale of the user of [ctx]; see [plural]. */
    public fun plural(ctx: UpdateContext<*>, key: String, count: Long, vararg args: Any?): String =
        plural(ctx.locale, key, count, *args)

    /** Whether [key] has a text for [locale] or the [fallback] language. */
    public fun has(locale: String?, key: String): Boolean = lookup(locale) { it.map[key] } != null

    override fun toString(): String = "Translations(fallback=$fallback, languages=$languages)"

    // ------------------------------------------------------------ internals

    private class Texts(val language: String, val map: Map<String, String>)

    private fun <T : Any> lookup(locale: String?, find: (Texts) -> T?): T? {
        for (tag in candidates(locale)) {
            val map = texts[tag] ?: continue
            find(Texts(tag.substringBefore('-'), map))?.let { return it }
        }
        return null
    }

    /** The full locale, its language, the fallback and its language, without repeats. */
    private fun candidates(locale: String?): Set<String> {
        val result = LinkedHashSet<String>(4)
        for (tag in listOfNotNull(locale?.let(::normalize), normalize(fallback))) {
            result += tag
            result += tag.substringBefore('-')
        }
        return result
    }

    public companion object {
        private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_]+)}")
        private val SLAVIC = setOf("ru", "uk", "be")

        /**
         * Translations from maps: language (`ru`, `en`, `pt-BR`) to texts by key.
         * @throws IllegalArgumentException if [texts] has no texts for [fallback].
         */
        @JvmStatic
        public fun of(fallback: String, texts: Map<String, Map<String, String>>): Translations {
            val result = Translations(fallback, texts)
            require(normalize(fallback) in result.texts) { "No texts for the fallback language $fallback" }
            return result
        }

        /**
         * Translations from the resource bundles [baseName] for [fallback] and [languages], e.g.
         * `messages_ru.properties` and `messages_en.properties` on the classpath (read as UTF-8).
         * `messages.properties` is used for a language without its own file.
         *
         * @throws java.util.MissingResourceException if there is no bundle for a language.
         */
        @JvmStatic
        public fun fromBundles(baseName: String, fallback: String, vararg languages: String): Translations {
            val loader = Thread.currentThread().contextClassLoader ?: Translations::class.java.classLoader
            val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT)
            val texts = (listOf(fallback) + languages).associateWith { language ->
                val bundle = ResourceBundle.getBundle(baseName, Locale.forLanguageTag(normalize(language)), loader, control)
                bundle.keySet().associateWith(bundle::getString)
            }
            return of(fallback, texts)
        }

        private fun normalize(locale: String): String = locale.trim().replace('_', '-').lowercase()

        /** The keys to try for [count] in [language], most specific first. */
        private fun pluralForms(language: String, count: Long): List<String> {
            if (language !in SLAVIC) return if (count == 1L) listOf("one", "other") else listOf("other")
            val mod10 = Math.floorMod(abs(count), 10L)
            val mod100 = Math.floorMod(abs(count), 100L)
            return when {
                mod10 == 1L && mod100 != 11L -> listOf("one", "other")
                mod10 in 2..4 && mod100 !in 12..14 -> listOf("few", "many", "other")
                else -> listOf("many", "other")
            }
        }

        private fun format(template: String, positional: List<Any?>, named: Map<String, Any?>): String =
            PLACEHOLDER.replace(template) { match ->
                val name = match.groupValues[1]
                val index = name.toIntOrNull()
                when {
                    index != null && index < positional.size -> positional[index].toString()
                    index == null && name in named -> named[name].toString()
                    else -> match.value
                }
            }
    }
}
