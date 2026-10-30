package ru.securitytrip.maxapi

import com.fasterxml.jackson.databind.ObjectMapper
import ru.securitytrip.maxapi.model.update.Update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The GraalVM metadata in `META-INF/native-image` must name every model class, as Jackson creates them by reflection. */
class NativeImageConfigTest {
    private val directory = "META-INF/native-image/io.github.securitytrip/maxapi"
    private val mapper = ObjectMapper()

    private fun read(name: String) = mapper.readTree(assertNotNull(javaClass.classLoader.getResource("$directory/$name"), name))

    @Test
    fun `reflection metadata lists exactly the model classes`() {
        val root = Paths.get(Update::class.java.protectionDomain.codeSource.location.toURI())
        val model = root.resolve("ru/securitytrip/maxapi/model")
        val classes = Files.walk(model).use { paths ->
            paths.filter { it.extension == "class" }
                .map { className(root, it) }
                .toList()
                .sorted()
        }
        val config = read("reflect-config.json")
        val listed = config.map { it["name"].asText() }
        assertEquals(classes, listed, "Regenerate $directory/reflect-config.json from the model classes")
        for (entry in config) {
            for (flag in listOf("allDeclaredConstructors", "allDeclaredMethods", "allDeclaredFields", "allPublicMethods")) {
                assertTrue(entry[flag].asBoolean(), "$flag of ${entry["name"]}")
            }
        }
    }

    @Test
    fun `resource metadata includes the root CA and the Kotlin built-ins`() {
        val patterns = read("resource-config.json")["resources"]["includes"].map { Regex(it["pattern"].asText().replace("\\Q", "").replace("\\E", "")) }
        for (resource in listOf("ru/securitytrip/maxapi/client/russian-trusted-root-ca.pem", "kotlin/kotlin.kotlin_builtins")) {
            assertTrue(patterns.any { it.matches(resource) }, resource)
            assertNotNull(javaClass.classLoader.getResource(resource), resource)
        }
    }

    private fun className(root: Path, file: Path): String =
        file.relativeTo(root).invariantSeparatorsPathString.removeSuffix(".class").replace('/', '.')
}
