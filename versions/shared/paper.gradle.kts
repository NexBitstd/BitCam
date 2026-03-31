import dev.nexbit.bitcam.gradle.loadBitCamVersionProperties
import io.papermc.paperweight.userdev.ReobfArtifactConfiguration
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
}

val targetJavaVersion = (rootProject.property("java_version") as String).toInt()
val versionProperties = loadBitCamVersionProperties(project)
fun versionProperty(name: String): String = versionProperties[name]
    ?: error("Missing $name in ${project.projectDir.parentFile.resolve("version.properties")}")
val mcVersion = versionProperty("minecraft_version")
val paperDevBundleVersion = versionProperty("paper_dev_bundle_version")
val paperApiVersion = versionProperty("paper_api_version")
val modName = rootProject.property("mod_name") as String
val modDescription = rootProject.property("mod_description") as String
val baseArchiveName = rootProject.property("archives_base_name") as String
val commonProject = project(":common")
val protocolProject = project(":protocol")
val serverCommonProject = project(":server-common")
val commonSourceSets = commonProject.extensions.getByType<SourceSetContainer>()
val protocolSourceSets = protocolProject.extensions.getByType<SourceSetContainer>()
val serverCommonSourceSets = serverCommonProject.extensions.getByType<SourceSetContainer>()
val commonMain = commonSourceSets.named("main").get()
val protocolMain = protocolSourceSets.named("main").get()
val serverCommonMain = serverCommonSourceSets.named("main").get()

base {
    archivesName.set("${baseArchiveName}-paper-${mcVersion}")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
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

tasks {
    runServer {
        minecraftVersion(mcVersion)
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
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
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

val outputDir = rootProject.layout.buildDirectory.dir("libs/paper")

tasks.named<Jar>("jar") {
    destinationDirectory.set(outputDir)
}

tasks.named<Jar>("sourcesJar") {
    destinationDirectory.set(outputDir)
}
