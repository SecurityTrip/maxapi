plugins {
    id("maxapi.library")
}

dependencies {
    api(project(":maxapi"))
    api(libs.opentelemetry.api)
    // Makes the span of an update current in the coroutines of its handler.
    implementation(libs.opentelemetry.extension.kotlin)

    testImplementation(kotlin("test"))
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}

mavenPublishing {
    pom {
        description.set("OpenTelemetry tracing for maxapi: spans of API requests and handled updates")
    }
}
