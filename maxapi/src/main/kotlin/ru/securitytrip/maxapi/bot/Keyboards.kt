package ru.securitytrip.maxapi.bot

import ru.securitytrip.maxapi.client.MaxLimits
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest
import ru.securitytrip.maxapi.model.keyboard.Button
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import java.util.function.Function

/**
 * A long list shown page by page in a keyboard: the buttons of the items on one page and a row that
 * turns the pages, `◀ 2/7 ▶`. The payloads of that row are packed by [callbackData] with the page and
 * an optional argument, e.g. the id of a category the list belongs to.
 *
 * ```kotlin
 * val catalog = Paginator("catalog", pageSize = 5)
 *
 * fun page(number: Int) = message {
 *     text("Products")
 *     attach(catalog.keyboard(products, number) { Buttons.callback(it.name, "product:${it.id}") })
 * }
 *
 * bot.onCommand("catalog") { reply(page(0)) }
 * bot.onCallback(catalog.filter()) { editMessage(page(catalog.page(this))) }
 * ```
 *
 * The layout keeps to the limits of Max ([MaxLimits]): at most [MaxLimits.BUTTONS_PER_ROW] buttons
 * in a row, and [MaxLimits.WIDE_BUTTONS_PER_ROW] in a row with a link or another wide button.
 *
 * @param prefix the prefix of the payloads; must not be empty or contain `:`.
 * @property pageSize items on a page.
 * @property columns item buttons in a row, 1 to [MaxLimits.BUTTONS_PER_ROW].
 * @property previousText text of the button to the previous page.
 * @property nextText text of the button to the next page.
 * @throws IllegalArgumentException if a page would not fit a keyboard of Max: more than
 *   [MaxLimits.KEYBOARD_ROWS] rows with the page row.
 */
public class Paginator @JvmOverloads constructor(
    prefix: String,
    public val pageSize: Int = 10,
    public val columns: Int = 1,
    public val previousText: String = "◀",
    public val nextText: String = "▶",
) {
    /** The format of the payloads of the page row: the page (zero-based) and the argument. */
    public val callbackData: CallbackData = CallbackData(prefix, "page", "arg")

    init {
        require(pageSize >= 1) { "pageSize must be >= 1" }
        require(columns in 1..MaxLimits.BUTTONS_PER_ROW) { "columns must be in 1..${MaxLimits.BUTTONS_PER_ROW}" }
        // 30 rows of at most 7 buttons also keep a page within the 210 buttons of a keyboard.
        val rows = (pageSize + columns - 1) / columns + 1
        require(rows <= MaxLimits.KEYBOARD_ROWS) { "A page of $pageSize items in $columns columns needs $rows rows; Max allows ${MaxLimits.KEYBOARD_ROWS}" }
    }

    /** Number of pages for [total] items; at least 1. */
    public fun pageCount(total: Int): Int = maxOf(1, (total + pageSize - 1) / pageSize)

    /** The items on [page]; a page out of range is clamped to the first or the last one. */
    public fun <T> items(items: List<T>, page: Int): List<T> {
        val from = clamp(page, items.size) * pageSize
        return items.subList(from, minOf(from + pageSize, items.size))
    }

    /**
     * The rows of [page]: the buttons that [button] makes for its items, then the page row if there
     * is more than one page. Empty if [items] is empty.
     *
     * @throws IllegalArgumentException if wide buttons need more rows than Max allows.
     */
    @JvmOverloads
    public fun <T> rows(items: List<T>, page: Int, arg: String = "", button: Function<in T, out Button>): List<List<Button>> {
        val navigation = navigation(page, pageCount(items.size), arg)
        val rows = layout(items(items, page).map { button.apply(it) }) + listOf(navigation).filter { it.isNotEmpty() }
        require(rows.size <= MaxLimits.KEYBOARD_ROWS) { "The page needs ${rows.size} rows; Max allows ${MaxLimits.KEYBOARD_ROWS}" }
        return rows
    }

    /**
     * The keyboard of [page], see [rows].
     * @throws IllegalArgumentException if [items] is empty: a keyboard needs a button.
     */
    @JvmOverloads
    public fun <T> keyboard(items: List<T>, page: Int, arg: String = "", button: Function<in T, out Button>): InlineKeyboardAttachmentRequest {
        val rows = rows(items, page, arg, button)
        require(rows.isNotEmpty()) { "A keyboard needs at least one button; the list is empty" }
        return InlineKeyboardAttachmentRequest(rows)
    }

    /**
     * The page row: `◀` to the previous page (not on the first), `2/7` with the current page and `▶`
     * to the next page (not on the last). Empty if there is only one page.
     */
    @JvmOverloads
    public fun navigation(page: Int, pageCount: Int, arg: String = ""): List<Button> {
        if (pageCount <= 1) return emptyList()
        val current = page.coerceIn(0, pageCount - 1)
        return listOfNotNull(
            if (current > 0) CallbackButton(previousText, callbackData.pack(current - 1, arg)) else null,
            CallbackButton("${current + 1}/$pageCount", callbackData.pack(current, arg)),
            if (current < pageCount - 1) CallbackButton(nextText, callbackData.pack(current + 1, arg)) else null,
        )
    }

    /**
     * The page a press of the page row asks for.
     * @throws IllegalArgumentException if the payload was not packed by [callbackData].
     */
    public fun page(ctx: CallbackContext): Int = ctx.data(callbackData).getValue("page").toIntOrNull() ?: 0

    /**
     * The argument of a press of the page row, empty if none was given.
     * @throws IllegalArgumentException if the payload was not packed by [callbackData].
     */
    public fun arg(ctx: CallbackContext): String = ctx.data(callbackData).getValue("arg")

    /** A filter for presses of the page row. */
    public fun filter(): Filter<CallbackContext> = callbackData.filter()

    private fun clamp(page: Int, total: Int): Int = page.coerceIn(0, pageCount(total) - 1)

    /** Rows of [columns] buttons; a row with a wide button holds at most [MaxLimits.WIDE_BUTTONS_PER_ROW]. */
    private fun layout(buttons: List<Button>): List<List<Button>> {
        val rows = mutableListOf<MutableList<Button>>()
        for (button in buttons) {
            val row = rows.lastOrNull()
            val wide = button.type in MaxLimits.WIDE_BUTTON_TYPES || row.orEmpty().any { it.type in MaxLimits.WIDE_BUTTON_TYPES }
            val limit = if (wide) minOf(columns, MaxLimits.WIDE_BUTTONS_PER_ROW) else columns
            if (row == null || row.size >= limit) rows += mutableListOf(button) else row += button
        }
        return rows
    }
}

/**
 * A "Yes / No" question in a keyboard. The payloads are packed by [callbackData] with the answer and
 * an optional argument, e.g. the id of what is confirmed.
 *
 * ```kotlin
 * val delete = Confirmation("delete")
 *
 * bot.onCommand("delete") {
 *     reply { text("Delete order 42?"); attach(delete.keyboard("42", yesText = "Да", noText = "Нет")) }
 * }
 * bot.onCallback(delete.yes()) { orders.delete(delete.arg(this)); editMessage(message { text("Deleted") }) }
 * bot.onCallback(delete.no()) { editMessage(message { text("Kept") }) }
 * ```
 *
 * @param prefix the prefix of the payloads; must not be empty or contain `:`.
 */
public class Confirmation(prefix: String) {
    /** The format of the payloads: the answer (`yes` or `no`) and the argument. */
    public val callbackData: CallbackData = CallbackData(prefix, "answer", "arg")

    /** The two buttons, for a row of your own keyboard. */
    @JvmOverloads
    public fun buttons(arg: String = "", yesText: String = "Yes", noText: String = "No"): List<Button> = listOf(
        CallbackButton(yesText, callbackData.pack(YES, arg)),
        CallbackButton(noText, callbackData.pack(NO, arg)),
    )

    /** A keyboard of one row with the two buttons. */
    @JvmOverloads
    public fun keyboard(arg: String = "", yesText: String = "Yes", noText: String = "No"): InlineKeyboardAttachmentRequest =
        InlineKeyboardAttachmentRequest(listOf(buttons(arg, yesText, noText)))

    /** A filter for presses of "Yes". */
    public fun yes(): Filter<CallbackContext> = Filters.of { callbackData.unpack(it.payload)?.get("answer") == YES }

    /** A filter for presses of "No". */
    public fun no(): Filter<CallbackContext> = Filters.of { callbackData.unpack(it.payload)?.get("answer") == NO }

    /** A filter for presses of either button. */
    public fun filter(): Filter<CallbackContext> = callbackData.filter()

    /**
     * Whether "Yes" was pressed.
     * @throws IllegalArgumentException if the payload was not packed by [callbackData].
     */
    public fun isYes(ctx: CallbackContext): Boolean = ctx.data(callbackData)["answer"] == YES

    /**
     * The argument of the press, empty if none was given.
     * @throws IllegalArgumentException if the payload was not packed by [callbackData].
     */
    public fun arg(ctx: CallbackContext): String = ctx.data(callbackData).getValue("arg")

    private companion object {
        const val YES = "yes"
        const val NO = "no"
    }
}
