import dev.nexbit.bitcam.gradle.loadBitCamVersionProperties
import io.papermc.paperweight.userdev.ReobfArtifactConfiguration
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    id("com.modrinth.minotaur")
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
}

val versionProperties = loadBitCamVersionProperties(project)
val targetJavaVersion = (versionProperties["java_version"] ?: rootProject.property("java_version") as String).toInt()
fun versionProperty(name: String): String = versionProperties[name]
    ?: error("Missing $name in ${project.projectDir.parentFile.resolve("version.properties")}")
val mcVersion = versionProperty("minecraft_version")
val paperDevBundleVersion = versionProperty("paper_dev_bundle_version")
val paperApiVersion = versionProperty("paper_api_version")
val modName = rootProject.property("mod_name") as String
val modDescription = rootProject.property("mod_description") as String
val baseArchiveName = rootProject.property("archives_base_name") as String
val modrinthProjectId = providers.gradleProperty("modrinth_project_id")
val modrinthVersionType = providers.gradleProperty("modrinth_version_type").orElse("release")
val commonProject = project(":common")
val protocolProject = project(":protocol")
val serverCommonProject = project(":server-common")
val commonSourceSets = commonProject.extensions.getByType<SourceSetContainer>()
val protocolSourceSets = protocolProject.extensions.getByType<SourceSetContainer>()
val serverCommonSourceSets = serverCommonProject.extensions.getByType<SourceSetContainer>()
val commonMain = commonSourceSets.named("main").get()
val protocolMain = protocolSourceSets.named("main").get()
val serverCommonMain = serverCommonSourceSets.named("main").get()
val sharedPaperMainJavaDir = rootProject.file("versions/paper/src/main/java")
val sharedPaperMainResourcesDir = rootProject.file("versions/paper/src/main/resources")
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/sources/bitcamBuildInfo/main/java")
val generateBitCamBuildInfo = tasks.register("generateBitCamBuildInfo") {
    inputs.property("minecraftVersion", mcVersion)
    outputs.dir(generatedBuildInfoDir)

    doLast {
        val outputFile = generatedBuildInfoDir.get()
            .file("dev/nexbit/bitcam/generated/BitCamBuildInfo.java")
            .asFile
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
    archivesName.set("${baseArchiveName}-paper-${mcVersion}")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

// Paper code is plain Bukkit/Paper API with no version directives, so it compiles as-is across all
// versions; no preprocessor needed (the per-version paperweight build still produces per-MC jars).
sourceSets.named("main") {
    java.setSrcDirs(listOf(sharedPaperMainJavaDir))
    resources.setSrcDirs(listOf(sharedPaperMainResourcesDir))
}

paperweight {
    reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

dependencies {
    paperweight.paperDevBundle(paperDevBundleVersion)
    implementation(commonProject)
    implementation(protocolProject)
    implementation(serverCommonProject)
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN").orElse(providers.gradleProperty("modrinth_token")))
    projectId.set(modrinthProjectId)
    versionNumber.set("${project.version}+paper.$mcVersion")
    versionName.set("$modName ${project.version} (Paper $mcVersion)")
    versionType.set(modrinthVersionType)
    uploadFile.set(tasks.named("jar"))
    gameVersions.set(listOf(mcVersion))
    loaders.set(listOf("paper"))
    changelog.set(providers.provider {
        rootProject.file("changelogs/${project.version}.md").readText()
    })
}

tasks {
    runServer {
        minecraftVersion(mcVersion)
        runDirectory(rootProject.file("runs/$mcVersion/paper/server"))
    }
}

tasks.processResources {
    inputs.properties(
        mapOf(
            "mod_name" to modName,
            "mod_description" to modDescription,
            "paper_api_version" to paperApiVersion,
            "version" to project.version
        )
    )
    filteringCharset = "UTF-8"

    filesMatching("plugin.yml") {
        expand(
            "mod_name" to modName,
            "mod_description" to modDescription,
            "paper_api_version" to paperApiVersion,
            "version" to project.version
        )
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
    from(rootProject.file("LICENSE.txt")) {
        rename { "${it}_${baseArchiveName}" }
    }
}

tasks.named<Jar>("sourcesJar") {
    from(commonMain.allSource)
    from(protocolMain.allSource)
    from(serverCommonMain.allSource)
}

val outputDir = rootProject.layout.buildDirectory.dir("libs")

tasks.named<Jar>("jar") {
    destinationDirectory.set(outputDir)
}

tasks.named<Jar>("sourcesJar") {
    destinationDirectory.set(outputDir)
}

tasks.named("modrinth") {
    dependsOn(rootProject.tasks.named("verifyModrinthChangelog"))
    dependsOn("build")
}
