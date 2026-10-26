plugins {
    id("maxapi.library")
}

dependencies {
    api(project(":maxapi"))
    api(libs.lettuce.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers)
}

mavenPublishing {
    pom {
        description.set("Redis storage for maxapi bots on Lettuce: dialog states, the polling marker and seen updates")
    }
}
