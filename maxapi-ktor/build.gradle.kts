plugins {
    id("maxapi.library")
}

dependencies {
    api(project(":maxapi"))
    api(libs.ktor.server.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

mavenPublishing {
    pom {
        description.set("Ktor server routing for maxapi bot webhooks")
    }
}
