pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // The NeoGradle userdev plugin (:neoforge) is published here, not on the plugin portal.
        maven {
            name = "NeoForged"
            url = uri("https://maven.neoforged.net/releases")
        }
    }
}

plugins {
    // Lets Gradle provision the JDK a subproject needs on machines that do not have both installed.
    // The Paper plugin compiles with Java 25, the NeoForge mod with Java 21; a contributor or the CI
    // runner only has to provide one of them (setup-java provides JAVA_HOME, the resolver downloads
    // the other). Without this, every machine would need both toolchains installed by hand, and the
    // old gradle.properties pinned an absolute Windows path that only exists on one machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "FiniteLoot"

include(":paper", ":neoforge")
