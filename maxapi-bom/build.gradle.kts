import com.vanniktech.maven.publish.JavaPlatform

// Bill of materials: import it once and leave the versions out of the other maxapi dependencies.
plugins {
    `java-platform`
    id("maxapi.publishing")
}

dependencies {
    constraints {
        api(project(":maxapi"))
        api(project(":maxapi-test"))
        api(project(":maxapi-spring-boot-starter"))
        api(project(":maxapi-ktor"))
    }
}

mavenPublishing {
    configure(JavaPlatform())
    pom {
        description.set("Versions of all maxapi modules")
    }
}
