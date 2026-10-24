plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":maxapi-spring-boot-starter"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.web)
}

kotlin {
    jvmToolchain(17)
}

application {
    // Run with: MAX_BOT_TOKEN=... gradlew :examples-spring:run
    mainClass.set("ru.securitytrip.maxapi.examples.spring.SpringBotApplicationKt")
}
