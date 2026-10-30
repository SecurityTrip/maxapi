pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "maxapi-parent"

// Published modules; each is published as io.github.securitytrip:<module name>.
include("maxapi")
include("maxapi-bom")
include("maxapi-test")
include("maxapi-spring-boot-starter")
include("maxapi-ktor")
include("maxapi-micrometer")
include("maxapi-jdbc")
include("maxapi-redis")
include("maxapi-opentelemetry")

include("examples")
include("examples-spring")
include("examples-ktor")

// Tests of whole applications: the Spring Boot starter with real PostgreSQL and Redis. Not published.
include("integration-tests")
