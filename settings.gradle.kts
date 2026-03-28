import java.io.File
import java.util.Properties

pluginManagement {
    val essentialGradleToolkitVersion = "0.8.5-SNAPSHOT"
    val fabricLoomVersion = providers.gradleProperty("fabric_loom_version").get()
    val moddevgradleVersion = providers.gradleProperty("moddevgradle_version").get()
    val paperweightUserdevVersion = providers.gradleProperty("paperweight_userdev_version").get()
    val runPaperVersion = providers.gradleProperty("run_paper_version").get()

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
        id("gg.essential.multi-version.root") version essentialGradleToolkitVersion
        id("fabric-loom") version fabricLoomVersion
        id("net.neoforged.moddev") version moddevgradleVersion
        id("io.papermc.paperweight.userdev") version paperweightUserdevVersion
        id("xyz.jpenilla.run-paper") version runPaperVersion
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
    val projectPath: String
        get() = ":$projectPrefix"

    fun loaderProjectPath(loader: String): String = "$projectPath:$loader"
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

            val loaders = listOf("fabric", "neoforge", "paper").filter { loader ->
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
        .sortedBy { it.minecraftVersion }
}

discoverIncludedVersionSets(rootDir).forEach { versionEntry ->
    include(versionEntry.projectPath)
    project(versionEntry.projectPath).projectDir = versionEntry.directory

    versionEntry.loaders.forEach { loader ->
        val loaderProjectPath = versionEntry.loaderProjectPath(loader)
        include(loaderProjectPath)
        project(loaderProjectPath).projectDir = versionEntry.directory.resolve(loader)
        project(loaderProjectPath).buildFileName = "../../shared/$loader.gradle.kts"
    }
}
