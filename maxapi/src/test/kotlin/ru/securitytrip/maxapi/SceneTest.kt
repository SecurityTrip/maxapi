package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.DialogState
import ru.securitytrip.maxapi.bot.Handler
import ru.securitytrip.maxapi.bot.InMemoryStateStorage
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.MessageContext
import ru.securitytrip.maxapi.bot.Scene
import ru.securitytrip.maxapi.bot.SceneContext
import ru.securitytrip.maxapi.bot.StateKey
import ru.securitytrip.maxapi.bot.scene
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.user.BotCommand
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneTest {
    private val api = FakeApi()
    private val storage = InMemoryStateStorage()
    private val bot = MaxBot(api.client, BotConfig(stateStorage = storage))
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val log = CopyOnWriteArrayList<String>()
    private val key = StateKey(100, 1)

    init {
        bot.onError { e, _ -> errors += e }
    }

    @AfterTest
    fun tearDown() {
        bot.close()
        api.close()
        assertEquals(emptyList(), errors)
    }

    private val survey = scene("survey") {
        enterOnCommand("survey", "Take the survey")
        enterOnCallback("start-survey")
        step("name") { prompt("What is your name?") }
        step("age") {
            prompt { reply("How old are you, ${answers["name"]}?") }
            validate("Send a number") { it.toIntOrNull() != null }
            validate("Too old") { it.toInt() < 150 }
        }
        step("rating") {
            prompt {
                reply {
                    text("Rate us")
                    keyboard {
                        row((1..3).map { option("$it ⭐", "$it") })
                        row(backButton("Back"), cancelButton("Cancel"))
                    }
                }
            }
        }
        onFinish { log += "finish $answers last=$answer step=$step" }
        onCancel { log += "cancel $answers" }
    }

    private suspend fun send(text: String?) = bot.processUpdate(FakeApi.message(text))

    private suspend fun press(payload: String) =
        bot.processUpdate(FakeApi.press(payload, listOf(listOf(CallbackButton("Pressed", payload)))))

    private suspend fun state() = storage.get(key)

    @Test
    fun `a scene asks its steps, checks answers and finishes`(): Unit = runBlocking {
        bot.include(survey)
        bot.onMessage { log += "fallback $text" }

        send("/survey")
        assertEquals(DialogState("scene:survey:name"), state())
        send("Ann")
        send("abc")
        send("200")
        send("30")
        assertEquals(DialogState("scene:survey:rating", mapOf("name" to "Ann", "age" to "30")), state())
        assertEquals(
            listOf("scene:survey:rating:option:1", "scene:survey:rating:option:2", "scene:survey:rating:option:3"),
            api.lastButtons[0],
        )
        assertEquals(listOf("scene:survey:rating:back:", "scene:survey:rating:cancel:"), api.lastButtons[1])

        press("scene:survey:rating:option:2")
        assertNull(state())
        assertEquals(
            listOf(
                "What is your name?", "How old are you, Ann?",
                "Send a number", "How old are you, Ann?",
                "Too old", "How old are you, Ann?",
                "Rate us",
            ),
            api.texts,
        )
        assertEquals(listOf("Pressed"), api.notifications)
        assertEquals(listOf("finish {name=Ann, age=30, rating=2} last=2 step=null"), log)

        send("after")
        assertEquals("fallback after", log.last())
        assertEquals(listOf(BotCommand("survey", "Take the survey")), bot.commands())
        assertEquals("Scene(survey, [name, age, rating])", survey.toString())
        assertEquals(listOf("name", "age", "rating"), survey.steps)
        assertEquals("survey", survey.name)
    }

    @Test
    fun `back goes to the previous step and cancel ends the scene`(): Unit = runBlocking {
        bot.include(survey)
        send("/survey")
        send("/back")
        assertEquals("scene:survey:name", state()?.name)
        send("Ann")
        send("/back")
        assertEquals(DialogState("scene:survey:name", mapOf("name" to "Ann")), state())
        send("Bob")
        send("40")
        press("scene:survey:rating:back:")
        assertEquals("scene:survey:age", state()?.name)
        send("41")
        press("scene:survey:rating:cancel:")
        assertNull(state())
        assertEquals(listOf("cancel {name=Bob, age=41}"), log)

        send("/survey")
        send("/cancel")
        assertNull(state())
        assertEquals("cancel {}", log.last())
    }

    @Test
    fun `other commands and stale buttons are not answers`(): Unit = runBlocking {
        bot.include(survey)
        bot.onCommand("help") { log += "help" }
        bot.onCallback { log += "other press $payload" }
        send("/survey")
        send("/help")
        assertEquals(listOf("help"), log)
        assertEquals("scene:survey:name", state()?.name)

        // An option of another step, a button of another scene and a malformed payload.
        press("scene:survey:rating:option:3")
        press("scene:quiz:name:cancel:")
        press("scene:x")
        assertEquals("scene:survey:name", state()?.name)
        assertEquals(3, log.count { it.startsWith("other press") })

        // Scene buttons outside the scene are not taken either.
        send("/cancel")
        press("scene:survey:name:cancel:")
        assertEquals(4, log.count { it.startsWith("other press") })
        send("/back")
        assertEquals(1, log.count { it == "cancel {}" })
    }

    @Test
    fun `a scene is entered by a button or from a handler with data`(): Unit = runBlocking {
        bot.include(survey)
        bot.onCommand("order") { survey.enter(this, mapOf("order" to "42")) }
        bot.onCommand("check") { log += "${survey.isActive(this)} ${survey.currentStep(this)}" }
        bot.onMessage(survey.filter()) { log += "never: the scene takes it first" }

        press("start-survey")
        assertEquals(listOf("Pressed"), api.notifications)
        assertEquals("scene:survey:name", state()?.name)

        send("/order")
        assertEquals(DialogState("scene:survey:name", mapOf("order" to "42")), state())
        send("/check")
        send("/cancel")
        send("/check")
        assertEquals(listOf("true name", "cancel {order=42}", "false null"), log)
    }

    @Test
    fun `a state of a step that no longer exists counts as the first step`(): Unit = runBlocking {
        bot.include(survey)
        storage.set(key, DialogState("scene:survey:removed", mapOf("x" to "1")))
        send("Ann")
        assertEquals(DialogState("scene:survey:age", mapOf("x" to "1", "name" to "Ann")), state())
    }

    @Test
    fun `invalid answers without errors, custom reactions and custom input`(): Unit = runBlocking {
        val quiz = scene("quiz") {
            enterOnCommand("quiz")
            cancelCommand("/Stop")
            backCommand(null)
            step("free") { prompt("Anything") }
            step("number") {
                prompt("A number")
                validate { it.all(Char::isDigit) }
                onInvalid { log += "invalid '$answer' at $step" }
            }
            step("quiet") {
                prompt("Quiet")
                validate("Not empty") { it.isNotBlank() }
                repeatPrompt(false)
            }
            step("upper") {
                prompt { reply("Upper"); log += "prompt after '$answer'" }
                input { ctx -> (ctx as? MessageContext)?.text?.uppercase() }
            }
        }
        bot.include(quiz)
        send("/quiz")
        send(null)
        assertEquals(listOf("Anything", "Anything"), api.texts)
        send("x")
        send("12a")
        send("12")
        send(" ")
        send("ok")
        send("/back")
        assertEquals("scene:quiz:upper", state()?.name)
        send("hello")
        assertNull(state())
        assertEquals(
            listOf("Anything", "Anything", "A number", "A number", "Quiet", "Not empty", "Upper"),
            api.texts,
        )
        assertEquals(listOf("invalid '12a' at number", "prompt after 'ok'"), log)

        send("/quiz")
        send("/stop")
        assertNull(state())
    }

    @Test
    fun `a custom input reads button presses too`(): Unit = runBlocking {
        val pick = scene("pick") {
            enterOnCommand("pick")
            step("color") {
                prompt { reply { text("Color?"); keyboard { row(option("Red", "red")) } } }
                input { ctx -> (ctx as? ru.securitytrip.maxapi.bot.CallbackContext)?.payload?.substringAfterLast(':')?.uppercase() }
            }
            onFinish { log += answers.getValue("color") }
        }
        bot.include(pick)
        send("/pick")
        send("typed")
        // A press without a matching button in the message is acknowledged with a check mark.
        bot.processUpdate(FakeApi.press("scene:pick:color:option:red"))
        assertEquals(listOf("RED"), log)
        assertEquals(listOf("✓"), api.notifications)
        assertEquals(listOf("Color?", "Color?"), api.texts)
    }

    @Test
    fun `Java builders and handlers`(): Unit = runBlocking {
        val java = Scene.builder("java")
            .enterOnCommand("java")
            .step("one", Consumer { step -> step.prompt(Handler<SceneContext> { it.replyBlocking("One") }) })
            .step("two", Consumer { step ->
                step.prompt("Two")
                    .validate("Digits") { it.all(Char::isDigit) }
                    .onInvalid(Handler<SceneContext> { log += "invalid ${it.answer}" })
            })
            .onFinish(Handler<SceneContext> { log += "finish ${it.answers}" })
            .onCancel(Handler<SceneContext> { log += "cancel" })
            .build()
        bot.include(java)
        bot.onCommand("enter") { java.enterBlocking(this) }
        bot.onCommand("enter2") { java.enterBlocking(this, mapOf("k" to "v")) }
        send("/java")
        send("a")
        send("b")
        send("1")
        send("/enter")
        send("/cancel")
        send("/enter2")
        assertEquals(DialogState("scene:java:one", mapOf("k" to "v")), state())
        assertEquals(listOf("invalid b", "finish {one=a, two=1}", "cancel"), log)
        assertEquals(listOf("One", "Two", "Two", "One", "One"), api.texts)
    }

    @Test
    fun `options are only available in steps`(): Unit = runBlocking {
        val broken = scene("broken") {
            enterOnCommand("broken")
            step("only") { prompt("Q") }
            onFinish { option("x", "y") }
        }
        bot.include(broken)
        send("/broken")
        send("a")
        assertEquals("option() is only available in a step", errors.single().message)
        errors.clear()
    }

    @Test
    fun `scenes are checked when built`() {
        assertFailsWith<IllegalArgumentException> { scene("empty") { } }
        assertFailsWith<IllegalArgumentException> { scene("a:b") { step("x") { prompt("x") } } }
        assertFailsWith<IllegalArgumentException> { scene("") { step("x") { prompt("x") } } }
        assertFailsWith<IllegalArgumentException> { scene("s") { step("x:y") { prompt("x") } } }
        assertFailsWith<IllegalArgumentException> { scene("s") { step("x") { prompt("x") }; step("x") { prompt("y") } } }
        val error = assertFailsWith<IllegalArgumentException> { scene("s") { step("x") { } } }
        assertEquals("Step x of scene s has no prompt", error.message)
        assertFalse(scene("s") { step("x") { prompt("x") } }.router.subscribedUpdateTypes().isNullOrEmpty())
        assertTrue(Scene.builder("s").step("x", Consumer { it.prompt("x") }).build().steps == listOf("x"))
    }
}
