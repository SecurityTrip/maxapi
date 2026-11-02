plugins {
    id("maxapi.library")
}

// One jar for Spring Boot 3 and 4. The code is compiled against the oldest supported Spring Boot 3, whose
// API Spring Boot 4 keeps; only the health API moved to other packages in Spring Boot 4, so the health
// contributor for it is compiled against Spring Boot 4 in the source set boot4 and packed into the same
// jar. MaxHealthAutoConfiguration imports the one for the Spring Boot on the classpath.
val boot4: SourceSet = sourceSets.create("boot4") {
    compileClasspath += sourceSets.main.get().output
}

tasks.jar {
    from(boot4.output)
}

tasks.matching { it.name == "sourcesJar" || it.name == "kotlinSourcesJar" }.configureEach {
    (this as Jar).from(boot4.allSource)
}

dependencies {
    api(project(":maxapi"))
    api(libs.spring.boot.autoconfigure)
    // Webhook mode needs Spring MVC; the application brings it with spring-boot-starter-web.
    compileOnly(platform(libs.spring.boot3.dependencies))
    compileOnly(libs.spring.webmvc)
    compileOnly(libs.jakarta.servlet)
    // Reactive web applications get the webhook endpoint with Spring WebFlux.
    compileOnly(libs.spring.webflux)
    // @MaxBotTest is used in the tests of applications, which have spring-test.
    compileOnly(libs.spring.test)
    // The health indicator is registered when the application has Spring Boot Actuator.
    compileOnly(libs.spring.boot.actuator)
    compileOnly(libs.spring.boot.actuator.autoconfigure)
    // Metrics are recorded when the application adds maxapi-micrometer.
    compileOnly(project(":maxapi-micrometer"))
    // max.bot.storage=jdbc or redis needs the application to add maxapi-jdbc or maxapi-redis.
    compileOnly(project(":maxapi-jdbc"))
    compileOnly(project(":maxapi-redis"))
    // Spans are recorded when the application adds maxapi-opentelemetry and has an OpenTelemetry bean.
    compileOnly(project(":maxapi-opentelemetry"))

    "boot4CompileOnly"(platform(libs.spring.boot.dependencies))
    "boot4CompileOnly"(project(":maxapi"))
    "boot4CompileOnly"(libs.spring.boot.autoconfigure)
    "boot4CompileOnly"(libs.spring.boot.health)
}

// The same tests run on the oldest Spring Boot 3 (test) and 4 (testBoot4), each on exactly the versions
// its BOM manages, as the dependency management plugin would give an application: a dependency the
// library needs newer fails them. Tests of the health API, which differs, are in src/testBoot3 and
// src/testBoot4.
val testBoot4: SourceSet = sourceSets.create("testBoot4") {
    compileClasspath += sourceSets.main.get().output + boot4.output
    runtimeClasspath += sourceSets.main.get().output + boot4.output
}
kotlin.sourceSets.named("test") { kotlin.srcDir("src/testBoot3/kotlin") }
kotlin.sourceSets.named("testBoot4") { kotlin.srcDirs("src/test/kotlin", "src/testBoot4/kotlin") }

// boot4 and its tests use internal declarations of main, as one module. (associateWith would put the jar,
// which has the classes of boot4, on the classpath of boot4.)
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileBoot4Kotlin") {
    friendPaths.from(sourceSets.main.get().output.classesDirs)
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestBoot4Kotlin") {
    friendPaths.from(sourceSets.main.get().output.classesDirs, boot4.output.classesDirs)
}

// The jar has the classes of boot4 in Spring Boot 3 applications too.
sourceSets.test { runtimeClasspath += boot4.output }

fun DependencyHandler.springTests(configuration: String, bom: Provider<MinimalExternalModuleDependency>) {
    add(configuration, kotlin("test-junit5"))
    add(configuration, enforcedPlatform(bom))
    for (library in listOf(
        libs.spring.boot.test, libs.spring.test, libs.spring.webmvc, libs.jakarta.servlet, libs.spring.webflux,
        libs.spring.core.test, libs.assertj, libs.okhttp.mockwebserver, libs.opentelemetry.sdk.testing, libs.h2,
        libs.testcontainers,
    )) {
        add(configuration, library)
    }
    for (module in listOf(":maxapi", ":maxapi-micrometer", ":maxapi-jdbc", ":maxapi-redis", ":maxapi-opentelemetry")) {
        add(configuration, project(module))
    }
    // Gradle runs JUnit with the launcher of the BOM's JUnit version.
    add(configuration.replace("Implementation", "RuntimeOnly"), "org.junit.platform:junit-platform-launcher")
}

dependencies {
    springTests("testImplementation", libs.spring.boot3.dependencies.asProvider())
    testImplementation(libs.spring.boot.actuator)
    testImplementation(libs.spring.boot.actuator.autoconfigure)

    springTests("testBoot4Implementation", libs.spring.boot.dependencies.asProvider())
    "testBoot4Implementation"(libs.spring.boot.autoconfigure)
    "testBoot4Implementation"(libs.spring.boot.health)
}

// Testcontainers is a tool of the tests, not a dependency of the library: the version the BOM of Spring
// Boot 3.4 manages cannot talk to current Docker engines.
configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy.force("org.testcontainers:testcontainers:${libs.versions.testcontainers.get()}")
}

val testBoot4Task = tasks.register<Test>("testBoot4") {
    description = "Runs the tests on Spring Boot 4."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = testBoot4.output.classesDirs
    classpath = testBoot4.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(testBoot4Task)
}

kover {
    currentProject {
        sources {
            excludedSourceSets.add("testBoot4")
        }
    }
}

mavenPublishing {
    pom {
        description.set("Spring Boot auto-configuration for maxapi bots: long polling or webhook from application properties")
    }
}
