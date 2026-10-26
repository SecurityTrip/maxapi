// Root project: aggregated API reference and the public API check of all library modules.
// Module builds are in the subprojects; shared settings are convention plugins in build-logic.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility)
}

// Public API dumps in <module>/api/<module>.api: `./gradlew apiCheck` (part of `check`) fails when the
// public API changes without `./gradlew apiDump`, so every API change is visible in review.
apiValidation {
    ignoredProjects += listOf("maxapi-bom", "examples", "examples-spring", "examples-ktor")
}

// `./gradlew :dokkaGenerate` builds the reference of all modules in build/dokka/html.
dokka {
    moduleName.set("maxapi")
}

dependencies {
    dokka(project(":maxapi"))
    dokka(project(":maxapi-test"))
    dokka(project(":maxapi-spring-boot-starter"))
    dokka(project(":maxapi-ktor"))
    dokka(project(":maxapi-micrometer"))
    dokka(project(":maxapi-jdbc"))
    dokka(project(":maxapi-redis"))
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
