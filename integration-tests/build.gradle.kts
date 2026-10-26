// Tests of whole applications built on the library: a Spring Boot application with the starter, a
// fake Max server, and PostgreSQL and Redis in Docker. Nothing here is published.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(project(":maxapi-spring-boot-starter"))
    testImplementation(project(":maxapi-jdbc"))
    testImplementation(project(":maxapi-redis"))
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.postgresql)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
