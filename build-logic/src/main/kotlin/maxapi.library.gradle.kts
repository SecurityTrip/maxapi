import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

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
