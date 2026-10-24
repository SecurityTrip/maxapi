plugins {
    id("maxapi.library")
}

dependencies {
    api(project(":maxapi"))
    api(libs.micrometer.core)

    testImplementation(kotlin("test"))
}

mavenPublishing {
    pom {
        description.set("Micrometer metrics for maxapi: API requests and handled updates")
    }
}
