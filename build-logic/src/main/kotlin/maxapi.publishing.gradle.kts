// Maven Central publishing (Central Portal) for every published module, libraries and the BOM.
// Credentials and the signing key come from Gradle properties, in CI from ORG_GRADLE_PROJECT_*
// environment variables; see "Releasing" in README.en.md.
plugins {
    id("com.vanniktech.maven.publish")
}

group = "io.github.securitytrip"
// Release builds pass the version from the git tag: ./gradlew -PreleaseVersion=1.2.3 ...
version = providers.gradleProperty("releaseVersion").getOrElse("1.0.0-SNAPSHOT")

mavenPublishing {
    publishToMavenCentral()
    // Local builds (publishToMavenLocal) work without a key; Maven Central rejects unsigned artifacts.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.securitytrip", project.name, version.toString())

    pom {
        name.set(project.name)
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
