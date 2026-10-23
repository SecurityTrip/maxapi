plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
    `java-library`
    `maven-publish`
}

group = "ru.securitytrip"
version = "0.1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.jackson.databind)
    api(libs.coroutines.core)
    implementation(libs.jackson.kotlin)
    implementation(libs.coroutines.jdk8)
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

dokka {
    moduleName.set("maxapi")
    dokkaSourceSets.main {
        includes.from("docs/module.md")
        jdkVersion.set(17)
        externalDocumentationLinks.register("kotlinx.coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
        }
        externalDocumentationLinks.register("jackson-databind") {
            url("https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/${libs.versions.jackson.get()}/")
        }
    }
}

// The standard javadoc tool cannot read Kotlin sources, so the javadoc jar ships the Dokka HTML instead.
tasks.javadoc {
    enabled = false
}

tasks.named<Jar>("javadocJar") {
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.test {
    useJUnitPlatform()
}

// `./gradlew check` fails if a change leaves library code untested. Branches have some slack:
// Kotlin generates null-check branches that cannot be reached, and code should not be bent to cover them.
kover {
    reports {
        verify {
            rule {
                minBound(100, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(95, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "maxapi"
            from(components["java"])
            pom {
                name.set("maxapi")
                description.set("Kotlin/Java client and bot framework for Max Bot API")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}
