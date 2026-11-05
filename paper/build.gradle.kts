plugins {
    java
    checkstyle
    id("com.gradleup.shadow") version "9.2.2"
}

// Both artefacts are published from one tag, so the file name has to answer "which server does this
// belong to" at a glance: <name>-<version>-<game version>-<loader>.jar. For the plugin the game
// version is the Paper line it compiles against (the API version), which is also the server version.
val paperMinecraftVersion: String by project

base {
    archivesName = "finiteloot"
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${paperMinecraftVersion}.build.+")
    compileOnly("org.xerial:sqlite-jdbc:3.51.1.0")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:${paperMinecraftVersion}.build.+")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.51.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }
    processResources {
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version)
        }
    }
    test {
        useJUnitPlatform()
    }
    shadowJar {
        archiveClassifier.set("")
        // The released file name carries the platform; the default <name>-<version>.jar does not.
        archiveFileName.set("finiteloot-${project.version}-${paperMinecraftVersion}-paper.jar")
        mergeServiceFiles()
    }
    jar {
        archiveClassifier.set("plain")
    }
    build {
        dependsOn(shadowJar)
    }
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}
