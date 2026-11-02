// Tests of whole applications built on the library: a Spring Boot application with the starter (on
// Spring MVC or WebFlux, with Actuator), a fake Max server, and PostgreSQL and Redis in Docker.
// Nothing here is published.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Enforced, as the dependency management plugin of Spring Boot applications does: libraries get
    // exactly the versions of the BOM, older ones included, so a library that needs a newer version fails here.
    testImplementation(enforcedPlatform(libs.spring.boot.dependencies.latest))
    testImplementation(project(":maxapi-spring-boot-starter"))
    testImplementation(project(":maxapi-jdbc"))
    testImplementation(project(":maxapi-redis"))
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.postgresql)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
    // The Kotlin library of Spring Boot comes from the BOM.
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

tasks.test {
    useJUnitPlatform()
}
