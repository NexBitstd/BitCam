    import java.io.File
import java.util.Properties

pluginManagement {
    val essentialGradleToolkitVersion = "0.7.2"
    val fabricLoomVersion = providers.gradleProperty("fabric_loom_version").get()
    val moddevgradleVersion = providers.gradleProperty("moddevgradle_version").get()
    val paperweightUserdevVersion = providers.gradleProperty("paperweight_userdev_version").get()
    val runPaperVersion = providers.gradleProperty("run_paper_version").get()
    val minotaurVersion = providers.gradleProperty("minotaur_version").get()

    repositories {
        google()
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io/") {
            name = "JitPack"
        }
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://maven.architectury.dev/") {
            name = "Architectury"
        }
        maven("https://repo.essential.gg/repository/maven-public") {
            name = "Essential"
        }
        maven("https://maven.neoforged.net/releases/") {
            name = "NeoForged"
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
    }

    plugins {
        id("gg.essential.defaults") version essentialGradleToolkitVersion
        id("gg.essential.multi-version") version essentialGradleToolkitVersion
        id("gg.essential.multi-version.root") version essentialGradleToolkitVersion
        id("fabric-loom") version fabricLoomVersion
        id("net.neoforged.moddev") version moddevgradleVersion
        id("io.papermc.paperweight.userdev") version paperweightUserdevVersion
        id("xyz.jpenilla.run-paper") version runPaperVersion
        id("com.modrinth.minotaur") version minotaurVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://jitpack.io/") {
            name = "JitPack"
        }
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://maven.architectury.dev/") {
            name = "Architectury"
        }
        maven("https://repo.essential.gg/repository/maven-public") {
            name = "Essential"
        }
        maven("https://maven.pkg.github.com/NexBitstd/JavaH264") {
            name = "JavaH264GitHubPackages"
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                    ?: ""
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: ""
            }
        }
        maven("https://maven.neoforged.net/releases/") {
            name = "NeoForged"
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
    }
}

rootProject.name = "BitCam"

include(":common")
include(":protocol")
include(":client-common")
include(":server-common")

data class IncludedVersionSet(
    val directory: File,
    val minecraftVersion: String,
    val projectPrefix: String,
    val loaders: List<String>
) {
    fun loaderProjectPath(loader: String): String = when (loader) {
        "fabric", "neoforge" -> ":client:$minecraftVersion-$loader"
        "paper" -> ":paper:$minecraftVersion-paper"
        else -> ":$projectPrefix:$loader"
    }
}

fun discoverIncludedVersionSets(rootDir: File): List<IncludedVersionSet> {
    val versionsDir = rootDir.resolve("versions")
    if (!versionsDir.isDirectory) {
        return emptyList()
    }

    return versionsDir.listFiles().orEmpty()
        .filter(File::isDirectory)
        .mapNotNull { versionDir ->
            val propertiesFile = versionDir.resolve("version.properties")
            if (!propertiesFile.isFile) {
                return@mapNotNull null
            }

            val properties = Properties().apply {
                propertiesFile.inputStream().use(::load)
            }

            val minecraftVersion = properties.getProperty("minecraft_version")
                ?: error("Missing minecraft_version in ${propertiesFile.relativeTo(rootDir)}")

            if (minecraftVersion != versionDir.name) {
                error(
                    "Version directory '${versionDir.name}' does not match minecraft_version=$minecraftVersion " +
                        "in ${propertiesFile.relativeTo(rootDir)}"
                )
            }

            val configuredLoaders = properties.getProperty("loaders")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: listOf("fabric", "neoforge", "paper")

            val loaders = configuredLoaders.filter { loader ->
                versionDir.resolve(loader).isDirectory
            }

            if (loaders.isEmpty()) {
                return@mapNotNull null
            }

            IncludedVersionSet(
                directory = versionDir,
                minecraftVersion = minecraftVersion,
                projectPrefix = "mc" + minecraftVersion.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_'),
                loaders = loaders
            )
        }
        .sortedWith { left, right ->
            compareVersionSortKeys(versionSortKey(left.minecraftVersion), versionSortKey(right.minecraftVersion))
        }
}

fun versionSortKey(version: String): List<Int> {
    val parts = Regex("\\d+").findAll(version).map { it.value.toInt() }.toList()
    return if (version.startsWith("1.")) {
        listOf(0) + parts.drop(1)
    } else {
        listOf(1) + parts
    }
}

fun compareVersionSortKeys(left: List<Int>, right: List<Int>): Int {
    val maxSize = maxOf(left.size, right.size)
    for (index in 0 until maxSize) {
        val leftPart = left.getOrElse(index) { 0 }
        val rightPart = right.getOrElse(index) { 0 }
        if (leftPart != rightPart) {
            return leftPart.compareTo(rightPart)
        }
    }
    return 0
}

include(":client")
project(":client").projectDir = rootDir.resolve("versions/client")
project(":client").buildFileName = "root.gradle.kts"

include(":paper")
project(":paper").projectDir = rootDir.resolve("versions/paper")
project(":paper").buildFileName = "root.gradle.kts"

discoverIncludedVersionSets(rootDir).forEach { versionEntry ->
    versionEntry.loaders.forEach { loader ->
        val loaderProjectPath = versionEntry.loaderProjectPath(loader)
        include(loaderProjectPath)
        project(loaderProjectPath).projectDir = versionEntry.directory.resolve(loader)
        project(loaderProjectPath).buildFileName =
            if (loader == "paper") "../../shared/paper.gradle.kts" else "../../shared/client.gradle.kts"
    }
}
