package ru.securitytrip.maxapi.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import ru.securitytrip.maxapi.model.update.Update

/**
 * JSON settings compatible with Max Bot API.
 *
 * Use it when you need to (de)serialize library models yourself, e.g. to parse webhook bodies
 * in your own HTTP layer or to store updates.
 */
public object MaxJson {
    /**
     * Creates a new [ObjectMapper] configured for the library models:
     *
     * - `snake_case` property names;
     * - `null` properties are not written (this matters: in some requests `null` and an empty value
     *   mean different things);
     * - unknown properties are ignored and unknown enum values become `UNKNOWN`;
     * - Kotlin default values are used for missing and `null` fields;
     * - sets keep the order of the JSON array.
     *
     * Each call returns an independent instance that you are free to reconfigure.
     */
    @JvmStatic
    public fun createMapper(): ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().enable(KotlinFeature.NullIsSameAsDefault).build())
        .addModule(SimpleModule().addAbstractTypeMapping(Set::class.java, LinkedHashSet::class.java))
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
        .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
        .build()

    internal val mapper: ObjectMapper = createMapper()

    /**
     * Parses an [Update] from JSON, e.g. a webhook request body.
     * @throws MaxException if [json] is not a valid update.
     */
    @JvmStatic
    public fun parseUpdate(json: String): Update = wrapParse { mapper.readValue(json, Update::class.java) }

    /**
     * Parses an [Update] from UTF-8 JSON bytes, e.g. a webhook request body.
     * @throws MaxException if [json] is not a valid update.
     */
    @JvmStatic
    public fun parseUpdate(json: ByteArray): Update = wrapParse { mapper.readValue(json, Update::class.java) }

    private inline fun <T> wrapParse(block: () -> T): T = try {
        block()
    } catch (e: JacksonException) {
        throw MaxException("Invalid update payload: ${e.originalMessage}", e)
    }
}
