plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":maxapi"))
}

kotlin {
    jvmToolchain(17)
}

application {
    // Run the Java example with: gradlew :examples:run -PmainClass=ru.securitytrip.maxapi.examples.JavaEchoBot
    mainClass.set(providers.gradleProperty("mainClass").orElse("ru.securitytrip.maxapi.examples.EchoBotKt"))
}
