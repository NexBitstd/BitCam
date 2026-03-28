package dev.nexbit.bitcam.gradle

import org.gradle.api.Project
import java.io.File
import java.util.Properties

private val loaderNames = listOf("fabric", "neoforge", "paper")
private val invalidProjectSegment = Regex("[^A-Za-z0-9]+")

data class BitCamVersionEntry(
    val directory: File,
    val minecraftVersion: String,
    val projectPrefix: String,
    val loaders: List<String>,
    val properties: Map<String, String>
) {
    val projectPath: String
        get() = ":$projectPrefix"

    fun loaderProjectPath(loader: String): String = "$projectPath:$loader"
}

fun discoverBitCamVersions(rootDir: File): List<BitCamVersionEntry> {
    val versionsDir = rootDir.resolve("versions")
    if (!versionsDir.isDirectory) {
        return emptyList()
    }

    return versionsDir
        .listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .mapNotNull { versionDir ->
            val propertiesFile = versionDir.resolve("version.properties")
            if (!propertiesFile.isFile) {
                return@mapNotNull null
            }

            val properties = loadProperties(propertiesFile)
            val minecraftVersion = properties["minecraft_version"]
                ?: error("Missing minecraft_version in ${propertiesFile.relativeTo(rootDir)}")

            if (minecraftVersion != versionDir.name) {
                error(
                    "Version directory '${versionDir.name}' does not match minecraft_version=$minecraftVersion " +
                        "in ${propertiesFile.relativeTo(rootDir)}"
                )
            }

            val loaders = loaderNames.filter { loader ->
                versionDir.resolve(loader).isDirectory
            }

            if (loaders.isEmpty()) {
                return@mapNotNull null
            }

            BitCamVersionEntry(
                directory = versionDir,
                minecraftVersion = minecraftVersion,
                projectPrefix = toProjectPrefix(minecraftVersion),
                loaders = loaders,
                properties = properties
            )
        }
        .sortedBy { it.minecraftVersion }
}

fun loadBitCamVersionProperties(project: Project): Map<String, String> {
    val versionDirectory = resolveVersionDirectory(project)
    return loadProperties(versionDirectory.resolve("version.properties"))
}

fun toProjectPrefix(minecraftVersion: String): String {
    val sanitized = minecraftVersion.replace(invalidProjectSegment, "_").trim('_')
    return "mc$sanitized"
}

private fun resolveVersionDirectory(project: Project): File {
    val direct = project.projectDir.resolve("version.properties")
    if (direct.isFile) {
        return project.projectDir
    }

    val parentDir = project.projectDir.parentFile
    if (parentDir != null) {
        val parentProperties = parentDir.resolve("version.properties")
        if (parentProperties.isFile) {
            return parentDir
        }
    }

    error("Unable to resolve version.properties for project ${project.path} in ${project.projectDir}")
}

private fun loadProperties(file: File): Map<String, String> {
    val properties = Properties()
    file.inputStream().use(properties::load)
    return properties.entries.associate { (key, value) -> key.toString() to value.toString() }
}
