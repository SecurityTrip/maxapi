plugins {
    id("maxapi.library")
}

dependencies {
    api(project(":maxapi"))

    testImplementation(kotlin("test"))
    testImplementation(libs.h2)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}

mavenPublishing {
    pom {
        description.set("JDBC storage for maxapi bots: dialog states, the polling marker and seen updates in any SQL database")
    }
}
