import org.gradle.api.tasks.JavaExec

plugins {
    `maven-publish`
    id("hytale-mod") version "0.+"
}

group = "com.hexvane"
version = "1.2.0"
val javaVersion = 25

repositories {
    mavenCentral()
    maven("https://maven.hytale-modding.info/releases") {
        name = "HytaleModdingReleases"
    }
}

dependencies {
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)
}

hytale {
    // Uncomment to add Assets.zip to external libraries (very large; IDE may become slow).
    //
    // addAssetsDependency = true

    // Uncomment to develop against the pre-release version of the game.
    //
    // updateChannel = "pre-release"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }

    withSourcesJar()
}

tasks.named<ProcessResources>("processResources") {
    var replaceProperties = mapOf(
        "plugin_group" to findProperty("plugin_group"),
        "plugin_maven_group" to project.group,
        "plugin_name" to project.name,
        "plugin_version" to project.version,
        "server_version" to findProperty("server_version"),

        "plugin_description" to findProperty("plugin_description"),
        "plugin_website" to findProperty("plugin_website"),

        "plugin_main_entrypoint" to findProperty("plugin_main_entrypoint"),
        "plugin_author" to findProperty("plugin_author")
    )

    filesMatching("manifest.json") {
        expand(replaceProperties)
    }

    inputs.properties(replaceProperties)
}

tasks.withType<Jar> {
    manifest {
        attributes["Specification-Title"] = rootProject.name
        attributes["Specification-Version"] = version
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] =
            providers.environmentVariable("COMMIT_SHA_SHORT")
                .map { "${version}-${it}" }
                .getOrElse(version.toString())
    }
}

publishing {
    repositories {
        // Put publish repositories here, not dependency repositories.
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

val syncAssets = tasks.register<Copy>("syncAssets") {
    group = "hytale"
    description = "Automatically syncs assets from Build back to Source after server stops."

    from(layout.buildDirectory.dir("resources/main"))
    into("src/main/resources")
    exclude("manifest.json")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    doLast {
        println("Assets successfully synced from game to source code.")
    }
}

afterEvaluate {
    val runServerTask = tasks.findByName("runServer") ?: tasks.findByName("server")
    if (runServerTask == null) {
        logger.warn("Could not find 'runServer' or 'server' task (hytale-mod). syncAssets not hooked.")
        return@afterEvaluate
    }
    if (runServerTask !is JavaExec) {
        logger.warn("Task '${runServerTask.name}' is not JavaExec; skipping sync hook and runServerNoSync.")
        return@afterEvaluate
    }
    val runServer = runServerTask as JavaExec
    runServer.jvmArgs = runServer.jvmArgs.filter { it.isBlank().not() }
    runServer.finalizedBy(syncAssets)
    logger.lifecycle("Task '${runServer.name}' finalized by syncAssets (copy build resources back to src on exit).")

    tasks.register<JavaExec>("runServerNoSync") {
        group = "hytale"
        description =
            "Same as runServer but does not run syncAssets afterward — safe when you edit src/main/resources while testing."
        classpath = runServer.classpath
        mainClass = runServer.mainClass
        mainModule = runServer.mainModule
        modularity.inferModulePath = runServer.modularity.inferModulePath
        jvmArgs = runServer.jvmArgs.filter { it.isBlank().not() }
        workingDir = runServer.workingDir
        args = runServer.args
        systemProperties = runServer.systemProperties
        environment = runServer.environment
        standardInput = runServer.standardInput
        isIgnoreExitValue = runServer.isIgnoreExitValue
        javaLauncher = runServer.javaLauncher
        enableAssertions = runServer.enableAssertions
    }
    logger.lifecycle("Task 'runServerNoSync' registered (no post-exit asset sync).")
}
