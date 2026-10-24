plugins {
    id("maxapi.library")
}

dependencies {
    api(project(":maxapi"))

    testImplementation(kotlin("test"))
}

mavenPublishing {
    pom {
        description.set("Test kit for maxapi bots: runs handlers without a network and records API calls")
    }
}
