plugins {
    id("java-library")
    id("net.neoforged.gradle.userdev") version "7.1.38"
}

// Kotlin DSL does not expose gradle.properties entries as script variables (Groovy does), so pull
// them in explicitly. They live in the root gradle.properties because both artifacts share one build.
val mod_id: String by project
val mod_name: String by project
val mod_license: String by project
val mod_authors: String by project
val mod_description: String by project
val minecraft_version: String by project
val minecraft_version_range: String by project
val neo_version: String by project
val loader_version_range: String by project
val sqlite_version: String by project
val junit_version: String by project
val snakeyaml_version: String by project

base {
    // The Minecraft line belongs in the file name. A 1.21.1 modpack server must never pick up a build
    // for another game version by mistake, and the shared project version alone cannot say which one
    // this jar is for.
    archivesName = "finiteloot-$minecraft_version"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.neoforged:neoforge:${neo_version}")

    // SQLite is bundled into the mod jar and loaded by FML's jar-in-jar loader, so a server owner does
    // not have to drop a driver into the mods folder. Verified end to end: the driver resolves from the
    // nested jar on a real server.
    jarJar(implementation("org.xerial:sqlite-jdbc:${sqlite_version}")!!)

    // config.yml is YAML and the production server ships no YAML parser at all. SnakeYAML is the same
    // class of parser Paper used underneath its FileConfiguration, which is the point: an existing
    // config.yml is read the same way here. Pure Java, no natives, so it bundles like the driver does.
    jarJar(implementation("org.yaml:snakeyaml:${snakeyaml_version}")!!)

    // The data layer is plain JDBC and never touches Minecraft, so its tests run on a plain JUnit 5
    // launcher instead of NeoGradle's game-booting test run. That keeps the suite fast and runnable
    // when the game toolchain is unavailable.
    testImplementation("org.junit.jupiter:junit-jupiter:${junit_version}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:${sqlite_version}")
    testRuntimeOnly("org.yaml:snakeyaml:${snakeyaml_version}")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Each test creates its own database under build/test-data; give them a writable temp dir too.
    systemProperty("java.io.tmpdir", layout.buildDirectory.dir("tmp").get().asFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// The data layer is deliberately independent of Minecraft, so prove it: this task compiles and runs
// the suite against plain Java + sqlite-jdbc only. If someone ever leaks a Minecraft or NeoForge
// import into vip.naya.finiteloot.data, this task is what catches it.
val dataLayerTest by tasks.registering(Test::class) {
    description = "Runs the Minecraft-independent data layer tests in isolation."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/data/**")
    systemProperty("java.io.tmpdir", layout.buildDirectory.dir("tmp").get().asFile.absolutePath)
}

runs {
    configureEach {
        systemProperty("forge.logging.markers", "REGISTRIES")
        systemProperty("forge.logging.console.level", "debug")
        workingDirectory = project.layout.projectDirectory.dir("run").dir(name)
        modSource(project.sourceSets.main.get())
    }

    // NeoGradle already registers a "server" run; configure it rather than creating a duplicate.
    named("server") {
        systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
        argument("--nogui")
    }
}

tasks.withType<ProcessResources>().configureEach {
    val props = mapOf(
        "minecraft_version" to minecraft_version,
        "minecraft_version_range" to minecraft_version_range,
        "neo_version" to neo_version,
        "loader_version_range" to loader_version_range,
        "mod_id" to mod_id,
        "mod_name" to mod_name,
        "mod_license" to mod_license,
        // One version for the whole repository: the mod and the plugin are released together.
        "mod_version" to project.version.toString(),
        "mod_authors" to mod_authors,
        "mod_description" to mod_description,
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/neoforge.mods.toml")) {
        expand(props)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
