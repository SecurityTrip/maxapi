// Convention plugins shared by the published modules; see src/main/kotlin.
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
}
