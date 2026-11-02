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
    implementation(kotlin("reflect", libs.versions.kotlin.stdlib.get()))

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

// The versions of the dependencies the library is built against, in the jar: the Spring Boot starter
// checks on startup that the application does not have older ones.
val dependencyVersions by tasks.registering(WriteProperties::class) {
    destinationFile.set(layout.buildDirectory.file("generated/dependency-versions/ru/securitytrip/maxapi/dependencies.properties"))
    property("kotlin-stdlib", libs.versions.kotlin.stdlib.get())
    property("kotlinx-coroutines", libs.versions.coroutines.get())
    property("jackson", libs.versions.jackson.get())
}

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/dependency-versions")).builtBy(dependencyVersions))
}

tasks.test {
    // A test that waits for a request nobody answers fails instead of hanging the build.
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
    // SpecConformanceTest reads the API specification: a new schema must re-run the tests.
    inputs.file("spec/schema.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
}

mavenPublishing {
    pom {
        description.set("Kotlin/Java client and bot framework for Max Bot API")
    }
}
