plugins {
    id("maxapi.library")
}

dependencies {
    api(libs.okhttp)
    api(libs.jackson.databind)
    api(libs.coroutines.core)
    implementation(libs.jackson.kotlin)
    implementation(libs.coroutines.jdk8)
    // An explicit version, otherwise the published pom gets a dependency without one.
    implementation(kotlin("reflect", libs.versions.kotlin.get()))

    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.jackson.yaml)
}

dokka {
    dokkaSourceSets.main {
        externalDocumentationLinks.register("kotlinx.coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
        }
        externalDocumentationLinks.register("jackson-databind") {
            url("https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/${libs.versions.jackson.get()}/")
        }
    }
}

tasks.test {
    // SpecConformanceTest reads the API specification: a new schema must re-run the tests.
    inputs.file("spec/schema.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
}

mavenPublishing {
    pom {
        description.set("Kotlin/Java client and bot framework for Max Bot API")
    }
}
