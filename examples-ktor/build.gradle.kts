plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":maxapi-ktor"))
    implementation(libs.ktor.server.netty)
}

kotlin {
    jvmToolchain(17)
}

application {
    // Run with: MAX_BOT_TOKEN=... MAX_WEBHOOK_URL=https://... gradlew :examples-ktor:run
    mainClass.set("ru.securitytrip.maxapi.examples.ktor.KtorBotKt")
}
