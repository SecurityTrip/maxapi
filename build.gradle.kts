import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
    `java-library`
}

group = "io.github.securitytrip"
// Release builds pass the version from the git tag: ./gradlew -PreleaseVersion=1.2.3 ...
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

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
    // An explicit version, otherwise the published pom gets a dependency without one.
    implementation(kotlin("reflect", libs.versions.kotlin.get()))

    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
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

// The standard javadoc tool cannot read Kotlin sources; the javadoc jar ships the Dokka HTML instead.
tasks.javadoc {
    enabled = false
}

// org.gradle.jvm.tasks.Jar is the common base: the javadoc jar of the publish plugin extends it directly.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    metaInf {
        from(rootProject.file("LICENSE"))
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

// Maven Central (Central Portal). Credentials and the signing key come from Gradle properties,
// in CI from ORG_GRADLE_PROJECT_* environment variables; see "Releasing" in README.md.
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
    publishToMavenCentral()
    // Local builds (publishToMavenLocal) work without a key; Maven Central rejects unsigned artifacts.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.securitytrip", "maxapi", version.toString())

    pom {
        name.set("maxapi")
        description.set("Kotlin/Java client and bot framework for Max Bot API")
        inceptionYear.set("2026")
        url.set("https://github.com/SecurityTrip/maxapi")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("SecurityTrip")
                name.set("SecurityTrip")
                url.set("https://github.com/SecurityTrip")
            }
        }
        scm {
            url.set("https://github.com/SecurityTrip/maxapi")
            connection.set("scm:git:https://github.com/SecurityTrip/maxapi.git")
            developerConnection.set("scm:git:ssh://git@github.com/SecurityTrip/maxapi.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/SecurityTrip/maxapi/issues")
        }
    }
}
