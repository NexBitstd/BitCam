import dev.nexbit.bitcam.gradle.discoverBitCamVersions

plugins {
    base
}

allprojects {
    group = property("maven_group") as String
    version = property("mod_version") as String
}

val versionEntries = discoverBitCamVersions(rootDir)
val activeMinecraftVersion = providers.gradleProperty("active_minecraft_version").orNull
    ?: versionEntries.lastOrNull()?.minecraftVersion
    ?: error("No version sets found under versions/")

val allVersionLoaders = versionEntries.flatMap { versionEntry ->
    versionEntry.loaders.map(versionEntry::loaderProjectPath)
}

fun loaderProjectsFor(version: String): List<String> {
    val versionEntry = versionEntries.firstOrNull { it.minecraftVersion == version }
        ?: error("Unknown active_minecraft_version=$version. Available: ${versionEntries.joinToString { it.minecraftVersion }}")
    return versionEntry.loaders.map(versionEntry::loaderProjectPath)
}

val sharedMediaModules = listOf(
    ":common",
    ":protocol",
    ":client-common",
    ":server-common"
)

tasks.named("build") {
    dependsOn(sharedMediaModules.map { "$it:build" } + allVersionLoaders.map { "$it:build" })
}

tasks.register("buildCurrentVersion") {
    group = "build"
    description = "Builds every loader for active_minecraft_version."
    dependsOn(sharedMediaModules.map { "$it:build" } + loaderProjectsFor(activeMinecraftVersion).map { "$it:build" })
}

tasks.register("buildVersionMatrix") {
    group = "build"
    description = "Builds every detected Minecraft version and loader set."
    dependsOn(sharedMediaModules.map { "$it:build" } + allVersionLoaders.map { "$it:build" })
}

tasks.register("printVersionMatrix") {
    group = "help"
    description = "Prints detected versions and loader modules."
    doLast {
        versionEntries.forEach { versionEntry ->
            println("${versionEntry.minecraftVersion}: ${versionEntry.loaders.joinToString(", ")}")
        }
    }
}
