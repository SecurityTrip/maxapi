package ru.securitytrip.maxapi.spring

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory

/**
 * Hints for a GraalVM native image of a Spring application, applied by Spring AOT from
 * `META-INF/spring/aot.factories`:
 *
 * - reflection on every class of `ru.securitytrip.maxapi.model` (Jackson builds the models, including
 *   the subclasses of polymorphic hierarchies such as `Update`, by reflection);
 * - the Russian Trusted Root CA bundled with the client, and the Kotlin built-ins that
 *   `jackson-module-kotlin` reads.
 *
 * The `maxapi` jar also has the same reflection metadata in `META-INF/native-image`, for native images
 * built without Spring.
 */
public class MaxRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        val resolver = PathMatchingResourcePatternResolver(classLoader)
        val readers = SimpleMetadataReaderFactory(resolver)
        for (resource in resolver.getResources(MODEL_CLASSES)) {
            val name = readers.getMetadataReader(resource).classMetadata.className
            hints.reflection().registerType(TypeReference.of(name), *MODEL_MEMBERS)
        }
        hints.resources().registerPattern(ROOT_CA)
        hints.resources().registerPattern("kotlin/*.kotlin_builtins")
        hints.resources().registerPattern("kotlin/*/*.kotlin_builtins")
    }
}

private const val MODEL_CLASSES = "classpath*:ru/securitytrip/maxapi/model/**/*.class"

/** The CA certificate the client loads from its jar. */
internal val ROOT_CA: String = "ru/securitytrip/maxapi/client/russian-trusted-root-ca.pem"

/** What Jackson needs of a model class: its constructors, methods (getters, `@JsonAnySetter`) and fields. */
internal val MODEL_MEMBERS: Array<MemberCategory> = arrayOf(
    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
    MemberCategory.INVOKE_DECLARED_METHODS,
    MemberCategory.ACCESS_DECLARED_FIELDS,
)
