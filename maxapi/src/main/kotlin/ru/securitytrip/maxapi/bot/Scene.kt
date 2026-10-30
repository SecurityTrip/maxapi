package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachment
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.user.User
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * A wizard: questions asked one after another, with the answers kept in the dialog state, so it works
 * with any [StateStorage]. The user can go back a step and cancel; an answer that fails a check gets
 * an error and the question again.
 *
 * ```kotlin
 * val survey = scene("survey") {
 *     enterOnCommand("survey", "Take the survey")
 *     step("name") { prompt("What is your name?") }
 *     step("age") {
 *         prompt { reply("How old are you, ${answers["name"]}?") }
 *         validate("Send a number from 1 to 120") { it.toIntOrNull() in 1..120 }
 *     }
 *     step("rating") {
 *         prompt {
 *             reply {
 *                 text("How do you like the bot?")
 *                 keyboard {
 *                     row((1..5).map { option("$it ⭐", "$it") })
 *                     row(backButton("◀ Back"), cancelButton("Cancel"))
 *                 }
 *             }
 *         }
 *     }
 *     onFinish { reply("Thanks, ${answers["name"]}: ${answers["rating"]} ⭐") }
 *     onCancel { reply("Cancelled") }
 * }
 * bot.include(survey)
 * ```
 *
 * ```java
 * Scene survey = Scene.builder("survey")
 *         .enterOnCommand("survey")
 *         .step("name", step -> step.prompt("What is your name?"))
 *         .step("age", step -> step.prompt("How old are you?").validate("Send a number", s -> s.matches("\\d+")))
 *         .onFinish(ctx -> ctx.replyBlocking("Thanks, " + ctx.getAnswers().get("name")))
 *         .build();
 * bot.include(survey);
 * ```
 *
 * ## How it works
 *
 * - The scene is entered with a command ([Builder.enterOnCommand]), a button ([Builder.enterOnCallback])
 *   or from any handler with [enter]. Entering sets the dialog state to the first step and sends its prompt.
 * - The dialog state of a user in a scene is named `scene:<scene>:<step>`, and the answers are kept in
 *   [DialogState.data] under the names of the steps, next to the data passed to [enter].
 * - An answer is the text of a message, or the value of a button made with [SceneContext.option] in the
 *   prompt of the current step; [StepBuilder.input] reads other kinds of answers. A valid answer is
 *   stored and the next step is prompted; after the last step the state is cleared and
 *   [Builder.onFinish] runs.
 * - `/back` (or a [SceneContext.backButton]) prompts the previous step again; `/cancel` (or a
 *   [SceneContext.cancelButton]) clears the state and runs [Builder.onCancel]. Other commands are not
 *   taken as answers, so the handlers of the bot still get them.
 * - Presses of the scene's buttons are answered with a notification that shows the text of the button.
 *
 * Include the scene before catch-all handlers such as `onMessage { }`, which would take the answers.
 *
 * @property name the name of the scene; part of the state names and of the button payloads.
 */
public class Scene private constructor(builder: Builder) {
    public val name: String = builder.name

    /** Names of the steps, in order. */
    public val steps: List<String>

    private val stepDefinitions: List<StepBuilder> = builder.steps.toList()
    private val onFinish = builder.onFinish
    private val onCancel = builder.onCancel
    private val prefix = "$STATE_PREFIX$name:"

    init {
        require(stepDefinitions.isNotEmpty()) { "Scene $name has no steps" }
        steps = stepDefinitions.map { it.name }
        require(steps.toSet().size == steps.size) { "Step names of scene $name must be unique" }
        stepDefinitions.forEach { requireNotNull(it.prompt) { "Step ${it.name} of scene $name has no prompt" } }
    }

    /** The handlers of the scene; [BaseRouter.include] with a scene adds them. */
    public val router: Router = Router().apply {
        for ((command, description) in builder.entryCommands) {
            if (description == null) onCommand(command) { enter(this) } else onCommand(command, description) { enter(this) }
        }
        for (payload in builder.entryCallbacks) {
            onCallback(payload) {
                acknowledge(this)
                enter(this)
            }
        }
        onCallback({ controlOf(it) != null }) { control(this, controlOf(this)!!) }
        builder.cancelCommand?.let { command -> onMessage({ it.command == command && stepIndex(it) != null }) { cancel(this) } }
        builder.backCommand?.let { command -> onMessage({ it.command == command && stepIndex(it) != null }) { back(this) } }
        onMessage({ it.command == null && stepIndex(it) != null }) {
            answer(this, read(this, text))
        }
    }

    /**
     * Enters the scene: sets the dialog state to the first step, with [data] as its values, and sends
     * the prompt of the first step. Leaves any other scene or state.
     */
    @JvmOverloads
    public suspend fun enter(ctx: UpdateContext<*>, data: Map<String, String> = emptyMap()) {
        ctx.setState(prefix + steps[0], data)
        prompt(ctx, 0, data, null)
    }

    /** Blocking version of [enter] for Java handlers. */
    @JvmOverloads
    public fun enterBlocking(ctx: UpdateContext<*>, data: Map<String, String> = emptyMap()): Unit = runBlocking { enter(ctx, data) }

    /** Whether the dialog of [ctx] is in this scene. */
    public fun isActive(ctx: UpdateContext<*>): Boolean = stepIndex(ctx) != null

    /** The step the dialog of [ctx] is at, or `null` if it is not in this scene. */
    public fun currentStep(ctx: UpdateContext<*>): String? = stepIndex(ctx)?.let(steps::get)

    /** A filter for updates whose dialog is in this scene. */
    public fun filter(): Filter<UpdateContext<*>> = Filters.of(::isActive)

    override fun toString(): String = "Scene($name, $steps)"

    // ------------------------------------------------------------ internals

    /** Index of the current step; a step that no longer exists counts as the first one. */
    private fun stepIndex(ctx: UpdateContext<*>): Int? {
        val state = ctx.state?.takeIf { it.startsWith(prefix) } ?: return null
        return steps.indexOf(state.substring(prefix.length)).coerceAtLeast(0)
    }

    /** The fields of a press of this scene's buttons while the dialog is in it; `null` for other presses. */
    private fun controlOf(ctx: CallbackContext): Map<String, String>? {
        val fields = CONTROLS.unpack(ctx.payload)?.takeIf { it["scene"] == name } ?: return null
        val index = stepIndex(ctx) ?: return null
        // An option of an earlier question must not answer the current one.
        return fields.takeIf { it["action"] != OPTION || it["step"] == steps[index] }
    }

    private suspend fun control(ctx: CallbackContext, fields: Map<String, String>) {
        acknowledge(ctx)
        when (fields["action"]) {
            CANCEL -> cancel(ctx)
            BACK -> back(ctx)
            else -> answer(ctx, read(ctx, fields["value"]))
        }
    }

    /** The answer to the current step: what [StepBuilder.input] reads, or [default] without it. */
    private fun read(ctx: UpdateContext<*>, default: String?): String? {
        val input = stepDefinitions[stepIndex(ctx)!!].input ?: return default
        return input.apply(ctx)
    }

    private suspend fun answer(ctx: UpdateContext<*>, answer: String?) {
        val index = stepIndex(ctx)!!
        val step = stepDefinitions[index]
        val failed = if (answer == null) step.checks.firstOrNull() ?: NO_ANSWER else step.checks.firstOrNull { !it.test.test(answer) }
        if (failed != null) {
            val invalid = SceneContext(this, step.name, ctx.stateData, answer, ctx)
            val onInvalid = step.onInvalid
            if (onInvalid != null) invalid.onInvalid() else failed.error?.let { ctx.reply(it) }
            if (step.repeatPrompt) prompt(ctx, index, ctx.stateData, answer)
            return
        }
        val data = ctx.stateData + (step.name to answer!!)
        if (index == steps.lastIndex) {
            ctx.clearState()
            onFinish?.let { SceneContext(this, null, data, answer, ctx).it() }
            return
        }
        ctx.setState(prefix + steps[index + 1], data)
        prompt(ctx, index + 1, data, answer)
    }

    private suspend fun back(ctx: UpdateContext<*>) {
        val index = (stepIndex(ctx)!! - 1).coerceAtLeast(0)
        ctx.setState(prefix + steps[index])
        prompt(ctx, index, ctx.stateData, null)
    }

    private suspend fun cancel(ctx: UpdateContext<*>) {
        val data = ctx.stateData
        ctx.clearState()
        onCancel?.let { SceneContext(this, null, data, null, ctx).it() }
    }

    private suspend fun prompt(ctx: UpdateContext<*>, index: Int, data: Map<String, String>, answer: String?) {
        val step = stepDefinitions[index]
        step.prompt!!.invoke(SceneContext(this, step.name, data, answer, ctx))
    }

    /** Answers a button press with the text of the button, so the user sees it was taken. */
    private suspend fun acknowledge(ctx: CallbackContext) {
        val text = ctx.message?.body?.attachments.orEmpty()
            .filterIsInstance<InlineKeyboardAttachment>()
            .flatMap { it.payload.buttons.flatten() }
            .firstOrNull { it is CallbackButton && it.payload == ctx.payload }?.text
        ctx.answer(text ?: "✓")
    }

    internal fun payload(step: String?, action: String, value: String): String =
        CONTROLS.pack(name, step.orEmpty(), action, value)

    /**
     * Describes a [Scene]: its steps, how it is entered and what happens at the end. Kotlin code uses
     * it through [scene], Java code through [Scene.builder].
     */
    @MaxSceneDsl
    public class Builder internal constructor(internal val name: String) {
        internal val steps = mutableListOf<StepBuilder>()
        internal val entryCommands = mutableListOf<Pair<String, String?>>()
        internal val entryCallbacks = mutableListOf<String>()
        internal var cancelCommand: String? = "cancel"
        internal var backCommand: String? = "back"
        internal var onFinish: (suspend SceneContext.() -> Unit)? = null
        internal var onCancel: (suspend SceneContext.() -> Unit)? = null

        init {
            checkName(name, "Scene name")
        }

        /** Adds a step named [name]; the answer is kept under this name. */
        @JvmSynthetic
        public fun step(name: String, block: StepBuilder.() -> Unit): Builder = apply {
            steps += StepBuilder(name).apply(block)
        }

        /** Java version of [step]. */
        public fun step(name: String, configure: Consumer<StepBuilder>): Builder = step(name) { configure.accept(this) }

        /** Enters the scene on the command `/name`. */
        public fun enterOnCommand(name: String): Builder = apply { entryCommands += name to null }

        /** Enters the scene on the command `/name`, which is shown in the bot menu with [description]. */
        public fun enterOnCommand(name: String, description: String): Builder = apply { entryCommands += name to description }

        /** Enters the scene when a button with [payload] is pressed. */
        public fun enterOnCallback(payload: String): Builder = apply { entryCallbacks += payload }

        /** The command that cancels the scene, `cancel` by default; `null` turns it off. */
        public fun cancelCommand(name: String?): Builder = apply { cancelCommand = name?.removePrefix("/")?.lowercase() }

        /** The command that goes back a step, `back` by default; `null` turns it off. */
        public fun backCommand(name: String?): Builder = apply { backCommand = name?.removePrefix("/")?.lowercase() }

        /** Runs after the last answer, once the state is cleared; [SceneContext.answers] holds all answers. */
        public fun onFinish(block: suspend SceneContext.() -> Unit): Builder = apply { onFinish = block }

        /** Java version of [onFinish]; runs on `Dispatchers.IO`. */
        public fun onFinish(handler: Handler<SceneContext>): Builder = onFinish(handler.toSuspend())

        /** Runs when the user cancels, once the state is cleared; [SceneContext.answers] holds the answers so far. */
        public fun onCancel(block: suspend SceneContext.() -> Unit): Builder = apply { onCancel = block }

        /** Java version of [onCancel]; runs on `Dispatchers.IO`. */
        public fun onCancel(handler: Handler<SceneContext>): Builder = onCancel(handler.toSuspend())

        /**
         * Returns the scene.
         * @throws IllegalArgumentException if there are no steps, two steps have the same name or a step has no prompt.
         */
        public fun build(): Scene = Scene(this)
    }

    /** Describes a step of a [Scene]: its question, the checks of the answer and how the answer is read. */
    @MaxSceneDsl
    public class StepBuilder internal constructor(internal val name: String) {
        internal var prompt: (suspend SceneContext.() -> Unit)? = null
        internal val checks = mutableListOf<Check>()
        internal var onInvalid: (suspend SceneContext.() -> Unit)? = null
        internal var input: java.util.function.Function<UpdateContext<*>, String?>? = null
        internal var repeatPrompt = true

        init {
            checkName(name, "Step name")
        }

        /** Asks the question with a text message. */
        public fun prompt(text: String): StepBuilder = prompt { reply(text) }

        /** Asks the question; runs when the step is entered and again after an invalid answer. */
        public fun prompt(block: suspend SceneContext.() -> Unit): StepBuilder = apply { prompt = block }

        /** Java version of [prompt]; runs on `Dispatchers.IO`. */
        public fun prompt(handler: Handler<SceneContext>): StepBuilder = prompt(handler.toSuspend())

        /** Accepts only answers that pass [check]; checks run in the order they are added. */
        public fun validate(check: Predicate<String>): StepBuilder = apply { checks += Check(null, check) }

        /** Accepts only answers that pass [check], and replies [error] to the others. */
        public fun validate(error: String, check: Predicate<String>): StepBuilder = apply { checks += Check(error, check) }

        /**
         * Runs instead of replying the error of the failed check, e.g. to send a translated error;
         * [SceneContext.answer] is the rejected answer (`null` if the message had no text).
         */
        public fun onInvalid(block: suspend SceneContext.() -> Unit): StepBuilder = apply { onInvalid = block }

        /** Java version of [onInvalid]; runs on `Dispatchers.IO`. */
        public fun onInvalid(handler: Handler<SceneContext>): StepBuilder = onInvalid(handler.toSuspend())

        /**
         * Reads the answer from a message or a button press of the scene, e.g. the phone of a shared
         * contact; `null` means there is no answer and counts as invalid. By default the answer is
         * the text of a message or the value of an [SceneContext.option].
         */
        public fun input(extract: java.util.function.Function<UpdateContext<*>, String?>): StepBuilder = apply { input = extract }

        /** Whether the prompt is sent again after an invalid answer; `true` by default. */
        public fun repeatPrompt(value: Boolean): StepBuilder = apply { repeatPrompt = value }
    }

    internal class Check(val error: String?, val test: Predicate<String>)

    public companion object {
        /** Starts describing a scene named [name] in Java code. */
        @JvmStatic
        public fun builder(name: String): Builder = Builder(name)

        private const val STATE_PREFIX = "scene:"
        internal const val OPTION = "option"
        internal const val BACK = "back"
        internal const val CANCEL = "cancel"
        private val NO_ANSWER = Check(null) { false }

        /** Payloads of the buttons of scenes: `scene:<scene>:<step>:<action>:<value>`. */
        private val CONTROLS = CallbackData("scene", "scene", "step", "action", "value")

        private fun checkName(name: String, what: String) {
            require(name.isNotEmpty() && CallbackData.SEPARATOR !in name) { "$what must not be empty or contain '${CallbackData.SEPARATOR}'" }
        }

        // runInterruptible: like Java handlers, Java scene callbacks are interrupted when the handler is cancelled.
        private fun Handler<SceneContext>.toSuspend(): suspend SceneContext.() -> Unit = {
            val ctx = this
            runInterruptible(Dispatchers.IO) { handle(ctx) }
        }
    }
}

/** Marks the scene DSL, so that a step block cannot call the methods of the scene block by mistake. */
@DslMarker
public annotation class MaxSceneDsl

/**
 * Describes a [Scene] named [name] in Kotlin:
 *
 * ```kotlin
 * val survey = scene("survey") {
 *     enterOnCommand("survey")
 *     step("name") { prompt("What is your name?") }
 *     onFinish { reply("Thanks, ${answers["name"]}!") }
 * }
 * ```
 */
public fun scene(name: String, block: Scene.Builder.() -> Unit): Scene = Scene.Builder(name).apply(block).build()

/**
 * What the prompts and the callbacks of a [Scene] receive: the context of the update being handled,
 * with all its helpers ([reply], [setState], [locale], ...), plus the scene, the step and the answers.
 *
 * @property scene the scene.
 * @property step the step being prompted or checked; `null` in [Scene.Builder.onFinish] and [Scene.Builder.onCancel].
 * @property answers the answers so far, by step name, and the data passed to [Scene.enter].
 * @property answer the answer that led here: the answer to the previous step in a prompt, the rejected
 *   answer in [Scene.StepBuilder.onInvalid], the last answer in [Scene.Builder.onFinish]; `null` when
 *   there is none.
 */
public class SceneContext internal constructor(
    public val scene: Scene,
    public val step: String?,
    public val answers: Map<String, String>,
    public val answer: String?,
    context: UpdateContext<*>,
) : UpdateContext<Update>(context.update, context.dispatch) {
    override val chatId: Long? = context.chatId

    override val user: User? = context.user

    /**
     * A button that answers the current step with [value] when pressed; [text] is what it shows.
     * Presses of options of other steps are ignored.
     * @throws IllegalStateException outside a step, i.e. in `onFinish` and `onCancel`.
     */
    public fun option(text: String, value: String): CallbackButton =
        CallbackButton(text, scene.payload(checkNotNull(step) { "option() is only available in a step" }, Scene.OPTION, value))

    /** A button that goes back to the previous step. */
    public fun backButton(text: String): CallbackButton = CallbackButton(text, scene.payload(step, Scene.BACK, ""))

    /** A button that cancels the scene. */
    public fun cancelButton(text: String): CallbackButton = CallbackButton(text, scene.payload(step, Scene.CANCEL, ""))
}
