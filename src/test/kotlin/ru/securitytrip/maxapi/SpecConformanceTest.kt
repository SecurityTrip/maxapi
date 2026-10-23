package ru.securitytrip.maxapi

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.java.MaxAsyncClient
import ru.securitytrip.maxapi.java.MaxBlockingClient
import ru.securitytrip.maxapi.model.ApiEnum
import ru.securitytrip.maxapi.model.attachment.Attachment
import ru.securitytrip.maxapi.model.attachment.AttachmentRequest
import ru.securitytrip.maxapi.model.attachment.UploadType
import ru.securitytrip.maxapi.model.chat.ChatAdminPermission
import ru.securitytrip.maxapi.model.chat.ChatStatus
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.keyboard.Button
import ru.securitytrip.maxapi.model.markup.MarkupElement
import ru.securitytrip.maxapi.model.markup.TextFormat
import ru.securitytrip.maxapi.model.message.MessageLinkType
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the library against the API specification in `spec/schema.yaml`.
 *
 * To support a new version of the API, replace that file: this test then lists the methods,
 * subtypes and enum values that are missing (or no longer exist).
 */
class SpecConformanceTest {
    private val spec: JsonNode = YAMLMapper().readTree(File("spec/schema.yaml"))
    private val schemas = spec["components"]["schemas"]

    private val operationIds: Set<String> = spec["paths"].properties()
        .flatMap { (_, operations) -> operations.properties().mapNotNull { (_, op) -> op["operationId"]?.asText() } }
        .toSet()

    @Test
    fun `the specification is read`() {
        assertEquals("Max Bot API", spec["info"]["title"].asText())
        assertTrue(operationIds.size >= 30, "found only $operationIds")
    }

    @Test
    fun `every operation has a method in every facade`() {
        for (facade in listOf(MaxClient::class.java, MaxBlockingClient::class.java, MaxAsyncClient::class.java)) {
            val methods = facade.methods.map { it.name }.toSet()
            assertEquals(emptySet(), operationIds - methods, "operations missing in ${facade.simpleName}")
        }
    }

    @Test
    fun `polymorphic families match the specification discriminators`() {
        val families = mapOf(
            "Update" to Update::class.java,
            "Attachment" to Attachment::class.java,
            "AttachmentRequest" to AttachmentRequest::class.java,
            "Button" to Button::class.java,
            "MarkupElement" to MarkupElement::class.java,
        )
        for ((schema, type) in families) {
            val inSpec = schemas[schema]["discriminator"]["mapping"].fieldNames().asSequence().toSet()
            val inLibrary = type.getAnnotation(JsonSubTypes::class.java).value.map { it.name }.toSet()
            assertEquals(inSpec, inLibrary, "subtypes of $schema")
        }
    }

    @Test
    fun `enums match the specification`() {
        val enums: Map<String, Array<out ApiEnum>> = mapOf(
            "ChatType" to ChatType.entries.toTypedArray(),
            "ChatStatus" to ChatStatus.entries.toTypedArray(),
            "ChatAdminPermission" to ChatAdminPermission.entries.toTypedArray(),
            "SenderAction" to SenderAction.entries.toTypedArray(),
            "UploadType" to UploadType.entries.toTypedArray(),
            "TextFormat" to TextFormat.entries.toTypedArray(),
            "MessageLinkType" to MessageLinkType.entries.toTypedArray(),
        )
        for ((schema, constants) in enums) {
            val inSpec = schemas[schema]["enum"].map { it.asText() }.toSet()
            // UNKNOWN is the library's fallback for values added to the API later.
            val inLibrary = constants.map { it.value }.toSet() - "unknown"
            assertEquals(inSpec, inLibrary, "values of $schema")
        }
    }

    @Test
    fun `update types match the update discriminator`() {
        val inSpec = schemas["Update"]["discriminator"]["mapping"].fieldNames().asSequence().toSet()
        assertEquals(inSpec, UpdateType.entries.map { it.value }.toSet())
    }
}
