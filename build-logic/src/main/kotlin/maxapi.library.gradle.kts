import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// A published Kotlin library module: explicit API, Java 17, tests on JUnit 5, full coverage,
// Dokka HTML in the javadoc jar, LICENSE in every jar.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    id("maxapi.publishing")
    `java-library`
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    // Built for the Kotlin library of the oldest supported Spring Boot (see gradle/libs.versions.toml):
    // the dependency management of Spring Boot applications replaces a newer kotlin-stdlib with the
    // version of its BOM, and code compiled for a newer API would fail there with NoSuchMethodError.
    coreLibrariesVersion = versionCatalogs.named("libs").findVersion("kotlin-stdlib").get().requiredVersion
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        // Default methods in interfaces, as Kotlin 2.2 and newer compile them by default.
        jvmDefault.set(JvmDefaultMode.ENABLE)
    }
}

// Libraries such as OkHttp ask for a newer kotlin-stdlib, which the dependency management of Spring Boot
// replaces with the version of its BOM. The code is compiled against that version, so that it cannot
// call anything newer.
configurations.named("compileClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:${versionCatalogs.named("libs").findVersion("kotlin-stdlib").get().requiredVersion}")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    // kotlin-test follows the Kotlin library version (see above) and would bring an old JUnit without the
    // launcher Gradle needs; a BOM of Spring Boot in the tests of a module still decides over this one.
    "testImplementation"(platform("org.junit:junit-bom:${versionCatalogs.named("libs").findVersion("junit").get().requiredVersion}"))
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

dokka {
    dokkaSourceSets.named("main") {
        jdkVersion.set(17)
        val moduleDocs = file("docs/module.md")
        if (moduleDocs.exists()) includes.from(moduleDocs)
    }
}

// `./gradlew check` fails if a change leaves library code untested. Branches have some slack:
// Kotlin generates null-check branches that cannot be reached, and code should not be bent to cover them.
kover {
    reports {
        verify {
            rule {
                minBound(100, CoverageUnit.LINE)
                minBound(95, CoverageUnit.BRANCH)
            }
        }
    }
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
}
