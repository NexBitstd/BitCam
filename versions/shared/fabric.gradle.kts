import dev.nexbit.bitcam.gradle.loadBitCamVersionProperties
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("fabric-loom")
}

val targetJavaVersion = (rootProject.property("java_version") as String).toInt()
val versionProperties = loadBitCamVersionProperties(project)
fun versionProperty(name: String): String = versionProperties[name]
    ?: error("Missing $name in ${project.projectDir.parentFile.resolve("version.properties")}")
val mcVersion = versionProperty("minecraft_version")
val fabricLoaderVersion = versionProperty("fabric_loader_version")
val fabricApiVersion = versionProperty("fabric_api_version")
val devauthVersion = rootProject.property("devauth_version") as String
val javacvVersion = rootProject.property("javacv_version") as String
val ffmpegVersion = rootProject.property("bytedeco_ffmpeg_version") as String
val modId = rootProject.property("mod_id") as String
val modName = rootProject.property("mod_name") as String
val modDescription = rootProject.property("mod_description") as String
val modLicense = rootProject.property("mod_license") as String
val baseArchiveName = rootProject.property("archives_base_name") as String
val commonProject = project(":common")
val protocolProject = project(":protocol")
val clientCommonProject = project(":client-common")
val serverCommonProject = project(":server-common")
val commonSourceSets = commonProject.extensions.getByType<SourceSetContainer>()
val protocolSourceSets = protocolProject.extensions.getByType<SourceSetContainer>()
val clientCommonSourceSets = clientCommonProject.extensions.getByType<SourceSetContainer>()
val serverCommonSourceSets = serverCommonProject.extensions.getByType<SourceSetContainer>()
val commonMain = commonSourceSets.named("main").get()
val protocolMain = protocolSourceSets.named("main").get()
val clientCommonMain = clientCommonSourceSets.named("main").get()
val serverCommonMain = serverCommonSourceSets.named("main").get()
val sharedClientJavaDir = rootProject.file("versions/shared/src/client/java")
val sharedClientResourcesDir = rootProject.file("versions/shared/src/client/resources")
val sharedServerJavaDir = rootProject.file("versions/shared/src/server/java")
val bytedecoDesktopClassifiers = listOf(
    "windows-x86_64",
    "linux-x86_64",
    "linux-arm64",
    "macosx-x86_64",
    "macosx-arm64"
)
val javaCvRuntimeLibraries = buildList {
    add("org.bytedeco:javacv:$javacvVersion")
    add("org.bytedeco:javacpp:$javacvVersion")
    add("org.bytedeco:ffmpeg:$ffmpegVersion")
    bytedecoDesktopClassifiers.forEach { classifier ->
        add("org.bytedeco:javacpp:$javacvVersion:$classifier")
        add("org.bytedeco:ffmpeg:$ffmpegVersion:$classifier")
    }
}

base {
    archivesName.set("${baseArchiveName}-fabric-${mcVersion}")
}

repositories {
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") {
        name = "DevAuth"
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()
    runs {
        val clientRunDir = project.relativePath(rootProject.file("runs/$mcVersion/fabric/client"))
        val serverRunDir = project.relativePath(rootProject.file("runs/$mcVersion/fabric/server"))
        configureEach {
            ideConfigGenerated(false)
        }
        named("client") {
            name("BitCam Fabric Client $mcVersion")
            runDir(clientRunDir)
        }
        named("server") {
            name("BitCam Fabric Server $mcVersion")
            runDir(serverRunDir)
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }
}

sourceSets.named("client") {
    java.srcDir(sharedClientJavaDir)
    resources.srcDir(sharedClientResourcesDir)
}

sourceSets.named("main") {
    java.srcDir(sharedServerJavaDir)
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(commonProject)
    implementation(protocolProject)
    implementation(serverCommonProject)
    add("clientImplementation", commonProject)
    add("clientImplementation", protocolProject)
    add("clientImplementation", clientCommonProject)
    add("clientImplementation", serverCommonProject)

    implementation("com.github.sarxos:webcam-capture:0.3.12")
    add("include", "com.github.sarxos:webcam-capture:0.3.12")
    add("include", "com.electronwill.night-config:toml:3.6.7")
    add("include", "com.electronwill.night-config:core:3.6.7")
    javaCvRuntimeLibraries.forEach { notation ->
        add("clientRuntimeOnly", notation)
        add("include", notation)
    }
    modRuntimeOnly("me.djtheredstoner:DevAuth-fabric:$devauthVersion")
}

tasks.processResources {
    inputs.properties(
        mapOf(
            "minecraft_version" to mcVersion,
            "fabric_loader_version" to fabricLoaderVersion,
            "mod_id" to modId,
            "mod_name" to modName,
            "mod_description" to modDescription,
            "mod_license" to modLicense,
            "version" to project.version
        )
    )
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "minecraft_version" to mcVersion,
            "fabric_loader_version" to fabricLoaderVersion,
            "mod_id" to modId,
            "mod_name" to modName,
            "mod_description" to modDescription,
            "mod_license" to modLicense,
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
    from(clientCommonMain.output)
    from(rootProject.file("LICENSE.txt")) {
        rename { "${it}_${baseArchiveName}" }
    }
}

tasks.named<Jar>("sourcesJar") {
    from(sourceSets.getByName("client").allSource)
    from(commonMain.allSource)
    from(protocolMain.allSource)
    from(serverCommonMain.allSource)
    from(clientCommonMain.allSource)
}

val outputDir = rootProject.layout.buildDirectory.dir("libs/fabric")

tasks.named("remapJar") {
    (this as Jar).destinationDirectory.set(outputDir)
}

tasks.named("remapSourcesJar") {
    (this as Jar).destinationDirectory.set(outputDir)
}
