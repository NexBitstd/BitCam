import java.io.File
import java.util.Properties

plugins {
    id("gg.essential.multi-version.root")
}

group = "${rootProject.group}.client-root"

data class ClientPreprocessNode(
    val name: String,
    val minecraftVersion: String,
    val minecraftNumber: Int,
    val loader: String
)

fun minecraftVersionToNumber(version: String): Int {
    val parts = Regex("\\d+").findAll(version).map { it.value.toInt() }.toList()
    return if (version.startsWith("1.")) {
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        10_000 + minor * 100 + patch
    } else {
        val year = parts.getOrElse(0) { 0 }
        val drop = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        year * 10_000 + drop * 100 + patch
    }
}

fun loadVersionProperties(versionDir: File): Properties {
    return Properties().apply {
        versionDir.resolve("version.properties").inputStream().use(::load)
    }
}

// Main project (real sources live there); everything else is generated from it via the graph.
val mainProjectName = projectDir.parentFile.listFiles().orEmpty()
    .map { it.resolve("mainProject") }
    .firstOrNull { it.isFile }
    ?.readText()?.trim()
    ?: "1.21.8-fabric"

val clientPreprocessNodes = projectDir.parentFile.listFiles().orEmpty()
    .filter { it.isDirectory && it.resolve("version.properties").isFile }
    .flatMap { versionDir ->
        val properties = loadVersionProperties(versionDir)
        val minecraftVersion = properties.getProperty("minecraft_version")
            ?: error("Missing minecraft_version in ${versionDir.resolve("version.properties")}")
        val loaders = properties.getProperty("loaders")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: listOf("fabric", "neoforge", "paper")

        loaders
            .filter { it == "fabric" || it == "neoforge" }
            .filter { versionDir.resolve(it).isDirectory }
            .map { loader ->
                ClientPreprocessNode(
                    name = "$minecraftVersion-$loader",
                    minecraftVersion = minecraftVersion,
                    minecraftNumber = minecraftVersionToNumber(minecraftVersion),
                    loader = loader
                )
            }
    }

preprocess {
    strictExtraMappings.set(false)

    val nodesByName = clientPreprocessNodes.associate { node ->
        node.name to createNode(node.name, node.minecraftNumber, "official")
    }

    fun linkNodes(child: ClientPreprocessNode, parent: ClientPreprocessNode) {
        val childNode = nodesByName.getValue(child.name)
        val parentNode = nodesByName.getValue(parent.name)
        val mappingsFile = projectDir.resolve("${child.minecraftVersion}-${parent.minecraftVersion}.txt")
        if (mappingsFile.isFile) {
            childNode.link(parentNode, mappingsFile)
        } else {
            childNode.link(parentNode)
        }
    }

    // Fabric chain anchored at the main project: every node links toward the main version.
    val fabricNodes = clientPreprocessNodes.filter { it.loader == "fabric" }.sortedBy { it.minecraftNumber }
    val mainIndex = fabricNodes.indexOfFirst { it.name == mainProjectName }.let { if (it < 0) fabricNodes.size / 2 else it }
    fabricNodes.forEachIndexed { index, node ->
        when {
            index < mainIndex -> linkNodes(node, fabricNodes[index + 1])
            index > mainIndex -> linkNodes(node, fabricNodes[index - 1])
        }
    }

    // NeoForge inherits the fabric node of the same MC version — a SINGLE preprocessing step, so
    // //#if/#elseif chains resolve correctly (a multi-step neoforge chain double-activates branches).
    // A per-version mappings file <mc>-<mc>.txt carries version renames into the //$$-commented
    // neoforge-only code as it is un-commented for neoforge. If no fabric twin exists, fall back to
    // the nearest lower neoforge node.
    val neoforgeNodes = clientPreprocessNodes.filter { it.loader == "neoforge" }.sortedBy { it.minecraftNumber }
    neoforgeNodes.forEach { neo ->
        val fabricTwin = fabricNodes.firstOrNull { it.minecraftVersion == neo.minecraftVersion }
        if (fabricTwin != null) {
            linkNodes(neo, fabricTwin)
        } else {
            val lowerNeo = neoforgeNodes.filter { it.minecraftNumber < neo.minecraftNumber }.maxByOrNull { it.minecraftNumber }
            if (lowerNeo != null) linkNodes(neo, lowerNeo)
        }
    }
}
