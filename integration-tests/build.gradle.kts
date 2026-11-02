// Tests of whole applications built on the library: a Spring Boot application with the starter (on
// Spring MVC or WebFlux, with Actuator), a fake Max server, and PostgreSQL and Redis in Docker.
// The same tests run on the newest Spring Boot 4 (test) and 3 (testBoot3). Nothing here is published.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

val testBoot3: SourceSet = sourceSets.create("testBoot3")
kotlin.sourceSets.named("testBoot3") { kotlin.srcDir("src/test/kotlin") }

// Enforced, as the dependency management plugin of Spring Boot applications does: libraries get
// exactly the versions of the BOM, older ones included, so a library that needs a newer version fails here.
fun DependencyHandler.application(configuration: String, bom: Provider<MinimalExternalModuleDependency>) {
    add(configuration, enforcedPlatform(bom))
    for (module in listOf(":maxapi-spring-boot-starter", ":maxapi-jdbc", ":maxapi-redis")) {
        add(configuration, project(module))
    }
    for (library in listOf(
        libs.spring.boot.starter.web, libs.spring.boot.starter.jdbc, libs.spring.boot.starter.webflux,
        libs.spring.boot.starter.actuator, libs.postgresql, libs.okhttp.mockwebserver, libs.testcontainers,
        libs.testcontainers.postgresql,
    )) {
        add(configuration, library)
    }
    add(configuration, kotlin("test-junit5"))
    add(configuration.replace("Implementation", "RuntimeOnly"), "org.junit.platform:junit-platform-launcher")
}

dependencies {
    application("testImplementation", libs.spring.boot.dependencies.latest)
    application("testBoot3Implementation", libs.spring.boot3.dependencies.latest)
}

// Testcontainers is a tool of the tests: the version Spring Boot 3 manages cannot talk to current Docker engines.
configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy.force("org.testcontainers:testcontainers:${libs.versions.testcontainers.get()}")
}

kotlin {
    jvmToolchain(17)
    // The Kotlin library of Spring Boot comes from the BOM: 1.9 in Spring Boot 3.
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

val testBoot3Task = tasks.register<Test>("testBoot3") {
    description = "Runs the integration tests on Spring Boot 3."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = testBoot3.output.classesDirs
    classpath = testBoot3.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(testBoot3Task)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
