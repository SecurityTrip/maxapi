package ru.securitytrip.maxapi.spring

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.aot.test.generate.TestGenerationContext
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.aot.ApplicationContextAotGenerator
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.test.tools.CompileWithForkedClassLoader
import org.springframework.core.test.tools.TestCompiler
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.model.attachment.PhotoToken
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UnknownUpdate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spring AOT, the build step of a GraalVM native image: the hints of the starter and an application run from generated code. */
class MaxAotTest {
    private val modelTypes = listOf(
        Update::class.java, MessageCreatedUpdate::class.java, MessageCallbackUpdate::class.java, UnknownUpdate::class.java,
        NewMessageBody::class.java, PhotoToken::class.java,
    )

    private fun assertModelHints(hints: RuntimeHints) {
        for (type in modelTypes) {
            assertTrue(
                RuntimeHintsPredicates.reflection().onType(type).withMemberCategories(*MODEL_MEMBERS).test(hints),
                "no reflection hints for $type",
            )
        }
        assertTrue(RuntimeHintsPredicates.resource().forResource(ROOT_CA).test(hints))
        assertTrue(RuntimeHintsPredicates.resource().forResource("kotlin/kotlin.kotlin_builtins").test(hints))
        assertTrue(RuntimeHintsPredicates.resource().forResource("kotlin/collections/collections.kotlin_builtins").test(hints))
    }

    @Test
    fun `the registrar covers every model class`() {
        val hints = RuntimeHints()
        MaxRuntimeHints().registerHints(hints, javaClass.classLoader)
        assertModelHints(hints)
        // Nested classes and companions too, e.g. the subclasses of polymorphic hierarchies.
        val registered = hints.reflection().typeHints().map { it.type.name }.toList()
        assertTrue(registered.size > 100, "only ${registered.size} types")
        assertTrue(registered.all { it.startsWith("ru.securitytrip.maxapi.model.") })
        assertTrue(hints.reflection().typeHints().allMatch { it.memberCategories.contains(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS) })
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        MaxStorageAutoConfiguration::class,
        MaxBotAutoConfiguration::class,
        MaxMetricsAutoConfiguration::class,
        MaxHealthAutoConfiguration::class,
        MaxTracingAutoConfiguration::class,
    )
    open class Application {
        @org.springframework.context.annotation.Bean
        open fun handlers(): MaxBotConfigurer = MaxBotConfigurer { bot -> bot.onMessage { handled += text.orEmpty() } }
    }

    // Generated code lives in the packages of the classes it configures and may use their package-private members.
    @Test
    @CompileWithForkedClassLoader
    fun `an application runs from the code generated ahead of time`() {
        val properties = TestPropertyValues.of("max.bot.token=t", "max.bot.mode=none")
        val build = AnnotationConfigApplicationContext()
        properties.applyTo(build)
        build.register(Application::class.java)

        val generation = TestGenerationContext()
        val initializer = ApplicationContextAotGenerator().processAheadOfTime(build, generation)
        generation.writeGeneratedContent()
        // The hints of aot.factories go to the native image along with the generated code.
        assertModelHints(generation.runtimeHints)

        TestCompiler.forSystem().with(generation).compile { compiled ->
            GenericApplicationContext().use { context ->
                properties.applyTo(context)
                @Suppress("UNCHECKED_CAST")
                val init = compiled.getInstance(ApplicationContextInitializer::class.java, initializer.reflectionName())
                    as ApplicationContextInitializer<GenericApplicationContext>
                init.initialize(context)
                context.refresh()

                val bot = context.getBean(MaxBot::class.java)
                bot.processUpdateBlocking(
                    MaxJson.parseUpdate(
                        """{"update_type":"message_created","timestamp":1,"message":{"recipient":{"chat_id":5},
                            "timestamp":1,"body":{"mid":"m","seq":1,"text":"aot"}}}""",
                    ),
                )
                assertEquals(listOf("aot"), handled)
                assertTrue(context.getBean(MaxBotLifecycle::class.java).isRunning)
            }
        }
    }

    private companion object {
        val handled = CopyOnWriteArrayList<String>()
    }
}
