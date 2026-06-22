import dev.nexbit.bitcam.gradle.loadBitCamVersionProperties
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("gg.essential.multi-version")
    id("com.modrinth.minotaur")
}

val loader = project.name.substringAfterLast('-')
val isFabric = loader == "fabric"
val isNeoForge = loader == "neoforge"
val isMainProject = project.name == project.file("../mainProject").readText().trim()

val versionProperties = loadBitCamVersionProperties(project)
val targetJavaVersion = (versionProperties["java_version"] ?: rootProject.property("java_version") as String).toInt()
fun versionProperty(name: String): String = versionProperties[name]
    ?: error("Missing $name in ${project.projectDir.parentFile.resolve("version.properties")}")
val mcVersion = versionProperty("minecraft_version")
val compileMcVersion = versionProperties["compile_minecraft_version"] ?: mcVersion
val minecraftDependency = versionProperties["minecraft_dependency"] ?: mcVersion
val minecraftVersionRange = versionProperties["minecraft_version_range"] ?: mcVersion
val devauthVersion = rootProject.property("devauth_version") as String
val javah264Version = rootProject.property("javah264_version") as String
val modId = rootProject.property("mod_id") as String
val modName = rootProject.property("mod_name") as String
val modDescription = rootProject.property("mod_description") as String
val modLicense = rootProject.property("mod_license") as String
val baseArchiveName = rootProject.property("archives_base_name") as String
val modrinthProjectId = providers.gradleProperty("modrinth_project_id")
val modrinthVersionType = providers.gradleProperty("modrinth_version_type").orElse("release")
val modrinthUploadTaskName = if ("remapJar" in tasks.names) "remapJar" else "jar"

val commonProject = project(":common")
val protocolProject = project(":protocol")
val clientCommonProject = project(":client-common")
val serverCommonProject = project(":server-common")
val commonMain = commonProject.extensions.getByType<SourceSetContainer>().named("main").get()
val protocolMain = protocolProject.extensions.getByType<SourceSetContainer>().named("main").get()
val clientCommonMain = clientCommonProject.extensions.getByType<SourceSetContainer>().named("main").get()
val serverCommonMain = serverCommonProject.extensions.getByType<SourceSetContainer>().named("main").get()

// Single-source: the main project (1.21.8-fabric) holds the real code here; the ReplayMod
// preprocessor generates the other versions/loaders from these dirs via the graph in root.gradle.kts.
val sharedSrcDirs = listOf(
    "versions/shared/src/server/java",
    "versions/shared/src/client/java",
    "versions/shared/src/fabric/main/java",
    "versions/shared/src/fabric/client/java",
    "versions/shared/src/neoforge/main/java"
).map { rootProject.file(it) }
val sharedResourceDirs = listOf(
    "versions/shared/src/client/resources",
    "versions/shared/src/main/resources"
).map { rootProject.file(it) }

// javah264 bundles its own per-platform natives (camera + H.264) inside the jar.
val javaH264Library = "dev.nexbit:javah264:$javah264Version"

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/bitcamBuildInfo/main/java")
val generateBitCamBuildInfo = tasks.register("generateBitCamBuildInfo") {
    inputs.property("minecraftVersion", mcVersion)
    outputs.dir(generatedBuildInfoDir)
    doLast {
        val outputFile = generatedBuildInfoDir.get()
            .file("dev/nexbit/bitcam/generated/BitCamBuildInfo.java").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package dev.nexbit.bitcam.generated;

            public final class BitCamBuildInfo {
                public static final String MINECRAFT_VERSION = "$mcVersion";

                private BitCamBuildInfo() {}
            }
            """.trimIndent()
        )
    }
}

base {
    archivesName.set("${baseArchiveName}-${loader}-${mcVersion}")
}

repositories {
    mavenLocal()
    maven("https://maven.pkg.github.com/NexBitstd/JavaH264") {
        name = "JavaH264GitHubPackages"
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR") ?: ""
            password = providers.gradleProperty("gpr.key").orNull
                ?: System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
    maven("https://libraries.minecraft.net/") { name = "MojangLibraries" }
    maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") { name = "DevAuth" }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

loom {
    runs {
        val clientRunDir = project.relativePath(rootProject.file("runs/$mcVersion/$loader/client"))
        val serverRunDir = project.relativePath(rootProject.file("runs/$mcVersion/$loader/server"))
        configureEach { ideConfigGenerated(false) }
        named("client") {
            name("BitCam ${loader} Client $mcVersion")
            runDir(clientRunDir)
        }
        named("server") {
            name("BitCam ${loader} Server $mcVersion")
            runDir(serverRunDir)
        }
    }
    if (isFabric) {
        mods {
            register(modId) {
                sourceSet(sourceSets.main.get())
            }
        }
    }
}

// Only the main project points at the real sources in versions/shared/src; non-main projects
// get their srcDirs replaced by the ReplayMod preprocessor's generated output automatically.
if (isMainProject) {
    sourceSets.named("main") {
        java.setSrcDirs(sharedSrcDirs)
        resources.setSrcDirs(sharedResourceDirs)
    }
}

dependencies {
    add("minecraft", "com.mojang:minecraft:$compileMcVersion")
    if (isFabric) {
        add("mappings", loom.officialMojangMappings())
        add("modImplementation", "net.fabricmc:fabric-loader:${versionProperty("fabric_loader_version")}")
        add("modImplementation", "net.fabricmc.fabric-api:fabric-api:${versionProperty("fabric_api_version")}")
        add("modRuntimeOnly", "me.djtheredstoner:DevAuth-fabric:$devauthVersion")
    } else {
        if (configurations.findByName("mappings") != null) {
            add("mappings", loom.officialMojangMappings())
        }
        add("neoForge", "net.neoforged:neoforge:${versionProperty("neoforge_version")}")
        add(
            if (configurations.findByName("modRuntimeOnly") != null) "modRuntimeOnly" else "runtimeOnly",
            "me.djtheredstoner:DevAuth-neoforge:$devauthVersion"
        )
    }

    add("implementation", commonProject)
    add("implementation", protocolProject)
    add("implementation", clientCommonProject)
    add("implementation", serverCommonProject)

    add("include", "com.electronwill.night-config:toml:3.6.7")
    add("include", "com.electronwill.night-config:core:3.6.7")
    add("runtimeOnly", javaH264Library)
    add("include", javaH264Library)
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN").orElse(providers.gradleProperty("modrinth_token")))
    projectId.set(modrinthProjectId)
    versionNumber.set("${project.version}+$loader.$mcVersion")
    versionName.set("$modName ${project.version} ($loader $mcVersion)")
    versionType.set(modrinthVersionType)
    uploadFile.set(tasks.named(modrinthUploadTaskName))
    gameVersions.set(listOf(mcVersion))
    loaders.set(listOf(loader))
    changelog.set(providers.provider {
        rootProject.file("changelogs/${project.version}.md").readText()
    })

    if (isFabric) {
        dependencies {
            required.project("fabric-api")
        }
    }
}

tasks.processResources {
    val expansions = mapOf(
        "minecraft_version" to minecraftDependency,
        "minecraft_version_range" to minecraftVersionRange,
        "fabric_loader_version" to (versionProperties["fabric_loader_version"] ?: ""),
        "neoforge_version" to (versionProperties["neoforge_version"] ?: ""),
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_description" to modDescription,
        "mod_license" to modLicense,
        "version" to project.version
    )
    inputs.properties(expansions)
    filteringCharset = "UTF-8"

    if (isFabric) {
        exclude("META-INF/neoforge.mods.toml")
        filesMatching("fabric.mod.json") { expand(expansions) }
    } else {
        exclude("fabric.mod.json")
        filesMatching("META-INF/neoforge.mods.toml") { expand(expansions) }
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(generateBitCamBuildInfo)
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.named<JavaCompile>("compileJava") {
    source(generatedBuildInfoDir)
}

tasks.named<Jar>("jar") {
    from(commonMain.output)
    from(protocolMain.output)
    from(serverCommonMain.output)
    from(clientCommonMain.output)
    from(rootProject.file("LICENSE.txt")) { rename { "${it}_${baseArchiveName}" } }
}

tasks.named<Jar>("sourcesJar") {
    from(commonMain.allSource)
    from(protocolMain.allSource)
    from(serverCommonMain.allSource)
    from(clientCommonMain.allSource)
}

val outputDir = rootProject.layout.buildDirectory.dir("libs")
tasks.matching { it.name == "remapJar" || it.name == "remapSourcesJar" }.configureEach {
    (this as Jar).destinationDirectory.set(outputDir)
}
tasks.named<Jar>("jar") { destinationDirectory.set(outputDir) }
tasks.named<Jar>("sourcesJar") { destinationDirectory.set(outputDir) }

tasks.named("modrinth") {
    dependsOn(rootProject.tasks.named("verifyModrinthChangelog"))
    dependsOn("build")
}
