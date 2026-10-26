plugins {
    id("maxapi.library")
}

dependencies {
    api(project(":maxapi"))
    api(libs.spring.boot.autoconfigure)
    // Webhook mode needs Spring MVC; the application brings it with spring-boot-starter-web.
    compileOnly(platform(libs.spring.boot.dependencies))
    compileOnly(libs.spring.webmvc)
    compileOnly(libs.jakarta.servlet)
    // Metrics are recorded when the application adds maxapi-micrometer.
    compileOnly(project(":maxapi-micrometer"))
    // max.bot.storage=jdbc or redis needs the application to add maxapi-jdbc or maxapi-redis.
    compileOnly(project(":maxapi-jdbc"))
    compileOnly(project(":maxapi-redis"))

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.test)
    testImplementation(libs.spring.webmvc)
    testImplementation(libs.jakarta.servlet)
    testImplementation(libs.assertj)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":maxapi-micrometer"))
    testImplementation(project(":maxapi-jdbc"))
    testImplementation(project(":maxapi-redis"))
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers)
}

mavenPublishing {
    pom {
        description.set("Spring Boot auto-configuration for maxapi bots: long polling or webhook from application properties")
    }
}
