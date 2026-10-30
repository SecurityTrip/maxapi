plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":maxapi"))

    testImplementation(kotlin("test"))
    testImplementation(project(":maxapi-test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

application {
    // Run the Java example with: gradlew :examples:run -PmainClass=ru.securitytrip.maxapi.examples.JavaEchoBot
    mainClass.set(providers.gradleProperty("mainClass").orElse("ru.securitytrip.maxapi.examples.EchoBotKt"))
}
