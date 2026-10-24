package ru.securitytrip.maxapi.bot

import ru.securitytrip.maxapi.model.attachment.Attachment
import ru.securitytrip.maxapi.model.chat.ChatType
import java.util.function.Predicate
import java.util.regex.Pattern

/**
 * A condition on a handler context, accepted by every `on*` method that takes a filter, from Kotlin
 * (as a function) and from Java (as a [Predicate]). Get filters from [Filters] and combine them with
 * [and], [or] and [not] in Kotlin, or with the `Predicate` methods `and`, `or` and `negate` in Java:
 *
 * ```kotlin
 * bot.onMessage(Filters.chatType(ChatType.DIALOG) and !Filters.hasText()) { reply("Send me text, please") }
 * ```
 *
 * ```java
 * // In Java, start with the most specific filter: and() accepts filters of the same or a more general context.
 * bot.onMessage(Filters.hasText().negate().and(Filters.fromUsers(42L)), ctx -> ...);
 * ```
 *
 * A filter of a general context fits handlers of more specific ones: `Filters.state("name")` is a
 * `Filter<UpdateContext<*>>` and can be passed to `onMessage` and `onCallback`.
 */
public abstract class Filter<in C> : (C) -> Boolean, Predicate<@UnsafeVariance C> {
    override fun test(t: @UnsafeVariance C): Boolean = invoke(t)
}

/** Matches when both this filter and [other] match. */
public infix fun <C> Filter<C>.and(other: Filter<C>): Filter<C> = Filters.of { this(it) && other(it) }

/** Matches when this filter or [other] matches. */
public infix fun <C> Filter<C>.or(other: Filter<C>): Filter<C> = Filters.of { this(it) || other(it) }

/** Matches when this filter does not. */
public operator fun <C> Filter<C>.not(): Filter<C> = Filters.of { !this(it) }

/**
 * Ready-made [Filter]s. Filters on [UpdateContext] properties fit any handler:
 * `bot.onCallback(Filters.fromUsers(42)) { ... }`.
 */
public object Filters {
    /** A filter from a predicate (a lambda in Kotlin and Java). */
    @JvmStatic
    public fun <C> of(predicate: Predicate<C>): Filter<C> = object : Filter<C>() {
        override fun invoke(ctx: C): Boolean = predicate.test(ctx)
    }

    // ------------------------------------------------------------ messages

    /** Messages that have text. */
    @JvmStatic
    public fun hasText(): Filter<MessageContext> = of { it.text != null }

    /** Messages whose text contains a match of [regex]; anchor it (`^...$`) to match the whole text. */
    @JvmStatic
    public fun text(regex: Regex): Filter<MessageContext> = of { ctx -> ctx.text?.let { regex.containsMatchIn(it) } == true }

    /** Java version of [text] with a regular expression. */
    @JvmStatic
    public fun text(pattern: Pattern): Filter<MessageContext> = text(pattern.toRegex())

    /** Messages whose text equals one of [values]. */
    @JvmStatic
    public fun textEquals(vararg values: String): Filter<MessageContext> {
        val set = values.toSet()
        return of { it.text in set }
    }

    /** Messages whose text equals one of [values], ignoring case. */
    @JvmStatic
    public fun textEqualsIgnoreCase(vararg values: String): Filter<MessageContext> {
        val set = values.mapTo(HashSet()) { it.lowercase() }
        return of { it.text?.lowercase() in set }
    }

    /** Messages whose text starts with [prefix]. */
    @JvmStatic
    public fun textStartsWith(prefix: String): Filter<MessageContext> = of { it.text?.startsWith(prefix) == true }

    /** Messages sent in a chat of one of [types]: [ChatType.DIALOG] for private messages, [ChatType.CHAT] for groups. */
    @JvmStatic
    public fun chatType(vararg types: ChatType): Filter<MessageContext> {
        val set = types.toSet()
        return of { it.message.recipient.chatType in set }
    }

    /** Messages with at least one attachment of [type] or its subclasses. */
    @JvmStatic
    public fun hasAttachment(type: Class<out Attachment>): Filter<MessageContext> =
        of { ctx -> ctx.message.body?.attachments.orEmpty().any { type.isInstance(it) } }

    /** Messages with at least one attachment of type [A]: `Filters.hasAttachment<PhotoAttachment>()`. */
    public inline fun <reified A : Attachment> hasAttachment(): Filter<MessageContext> = hasAttachment(A::class.java)

    // ----------------------------------------------------------- callbacks

    /** Button presses whose payload starts with [prefix]. */
    @JvmStatic
    public fun payloadStartsWith(prefix: String): Filter<CallbackContext> = of { it.payload?.startsWith(prefix) == true }

    // ------------------------------------------------------------ any update

    /** Updates caused by one of the users with [userIds]. */
    @JvmStatic
    public fun fromUsers(vararg userIds: Long): Filter<UpdateContext<*>> {
        val set = userIds.toSet()
        return of { it.user?.userId in set }
    }

    /** Updates from one of the chats with [chatIds]. */
    @JvmStatic
    public fun inChats(vararg chatIds: Long): Filter<UpdateContext<*>> {
        val set = chatIds.toSet()
        return of { it.chatId in set }
    }

    /** Updates whose dialog is in one of the states [names] (see [UpdateContext.state]). */
    @JvmStatic
    public fun state(vararg names: String): Filter<UpdateContext<*>> {
        val set = names.toSet()
        return of { it.state in set }
    }

    /** Updates whose dialog has no state. */
    @JvmStatic
    public fun noState(): Filter<UpdateContext<*>> = of { it.state == null }

    /** Updates whose dialog has some state. */
    @JvmStatic
    public fun anyState(): Filter<UpdateContext<*>> = of { it.state != null }
}
