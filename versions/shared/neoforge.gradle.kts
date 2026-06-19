import dev.nexbit.bitcam.gradle.loadBitCamVersionProperties
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugins.ide.idea.model.IdeaModel

plugins {
    id("net.neoforged.moddev")
}

val targetJavaVersion = (rootProject.property("java_version") as String).toInt()
val versionProperties = loadBitCamVersionProperties(project)
fun versionProperty(name: String): String = versionProperties[name]
    ?: error("Missing $name in ${project.projectDir.parentFile.resolve("version.properties")}")
val mcVersion = versionProperty("minecraft_version")
val neoforgeVersion = versionProperty("neoforge_version")
val devauthVersion = rootProject.property("devauth_version") as String
val javacvVersion = rootProject.property("javacv_version") as String
val ffmpegVersion = rootProject.property("bytedeco_ffmpeg_version") as String
val modId = rootProject.property("mod_id") as String
val modName = rootProject.property("mod_name") as String
val modDescription = rootProject.property("mod_description") as String
val modLicense = rootProject.property("mod_license") as String
val minecraftVersionRange = versionProperty("minecraft_version_range")
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
// The dev runtime only needs the natives for the machine it runs on; end users download just their
// own platform's natives at runtime (see CameraLibraryManager). This mirrors detectClassifier().
val hostBytedecoClassifier = run {
    val osName = System.getProperty("os.name", "").lowercase()
    val osArch = System.getProperty("os.arch", "").lowercase()
    val isArm = osArch.contains("aarch64") || osArch.contains("arm")
    when {
        osName.contains("win") -> "windows-x86_64"
        osName.contains("mac") -> if (isArm) "macosx-arm64" else "macosx-x86_64"
        else -> if (isArm) "linux-arm64" else "linux-x86_64"
    }
}
val bytedecoDesktopClassifiers = listOf(hostBytedecoClassifier)
val javaCvBundledLibraries = listOf(
    "org.bytedeco:javacv:$javacvVersion",
    "org.bytedeco:javacpp:$javacvVersion",
    "org.bytedeco:ffmpeg:$ffmpegVersion"
)
val webcamCaptureLibraries = listOf(
    "com.github.sarxos:webcam-capture:0.3.12",
    "com.nativelibs4java:bridj:0.7.0"
)
val javaCvNativeRuntimeLibraries = bytedecoDesktopClassifiers.flatMap { classifier ->
    listOf(
        "org.bytedeco:javacpp:$javacvVersion:$classifier",
        // GPL ffmpeg build: the only one bundling the libx264 H.264 encoder.
        "org.bytedeco:ffmpeg:$ffmpegVersion:$classifier-gpl"
    )
}

base {
    archivesName.set("${baseArchiveName}-neoforge-${mcVersion}")
}

repositories {
    maven("https://libraries.minecraft.net/") {
        name = "MojangLibraries"
    }
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") {
        name = "DevAuth"
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

sourceSets.named("main") {
    java.srcDir(sharedClientJavaDir)
    java.srcDir(sharedServerJavaDir)
    resources.srcDir(sharedClientResourcesDir)
}

neoForge {
    version = neoforgeVersion
    runs {
        create("client") {
            client()
            ideName = "BitCam NeoForge Client $mcVersion"
            gameDirectory = rootProject.file("runs/$mcVersion/neoforge/client")
        }
        create("server") {
            server()
            ideName = "BitCam NeoForge Server $mcVersion"
            gameDirectory = rootProject.file("runs/$mcVersion/neoforge/server")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(commonProject)
    implementation(protocolProject)
    implementation(clientCommonProject)
    implementation(serverCommonProject)
    additionalRuntimeClasspath(commonProject)
    additionalRuntimeClasspath(protocolProject)
    additionalRuntimeClasspath(serverCommonProject)
    additionalRuntimeClasspath(clientCommonProject)
    webcamCaptureLibraries.forEach { notation ->
        additionalRuntimeClasspath(notation)
    }
    javaCvBundledLibraries.forEach { notation ->
        additionalRuntimeClasspath(notation)
    }
    javaCvNativeRuntimeLibraries.forEach { notation ->
        additionalRuntimeClasspath(notation)
    }

    webcamCaptureLibraries.forEach { notation ->
        implementation(notation)
        jarJar(notation)
    }
    jarJar("com.electronwill.night-config:toml:3.6.7")
    jarJar("com.electronwill.night-config:core:3.6.7")
    javaCvBundledLibraries.forEach { notation ->
        runtimeOnly(notation)
        jarJar(notation)
    }
    javaCvNativeRuntimeLibraries.forEach { notation ->
        runtimeOnly(notation)
    }
    runtimeOnly("me.djtheredstoner:DevAuth-neoforge:$devauthVersion")
}

tasks.processResources {
    inputs.properties(
        mapOf(
            "minecraft_version_range" to minecraftVersionRange,
            "mod_id" to modId,
            "mod_name" to modName,
            "mod_description" to modDescription,
            "mod_license" to modLicense,
            "neoforge_version" to neoforgeVersion,
            "version" to project.version
        )
    )
    filteringCharset = "UTF-8"

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            "minecraft_version_range" to minecraftVersionRange,
            "mod_id" to modId,
            "mod_name" to modName,
            "mod_description" to modDescription,
            "mod_license" to modLicense,
            "neoforge_version" to neoforgeVersion,
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
    from(clientCommonMain.output)
    from(serverCommonMain.output)
    from(rootProject.file("LICENSE.txt")) {
        rename { "${it}_${baseArchiveName}" }
    }
}

tasks.named<Jar>("sourcesJar") {
    from(commonMain.allSource)
    from(protocolMain.allSource)
    from(clientCommonMain.allSource)
    from(serverCommonMain.allSource)
}

val outputDir = rootProject.layout.buildDirectory.dir("libs/neoforge")

tasks.named<Jar>("jar") {
    destinationDirectory.set(outputDir)
}

tasks.named<Jar>("sourcesJar") {
    destinationDirectory.set(outputDir)
}

project.afterEvaluate {
    val ideaModel = rootProject.extensions.findByType(IdeaModel::class.java) ?: return@afterEvaluate
    val ideaProject = ideaModel.project as? ExtensionAware ?: return@afterEvaluate
    val projectSettings = ideaProject.extensions.findByName("settings") as? ExtensionAware ?: return@afterEvaluate
    val runConfigurations = projectSettings.extensions.findByName("runConfigurations") ?: return@afterEvaluate

    val removeMethod = runConfigurations.javaClass.methods.firstOrNull { method ->
        method.name == "remove" && method.parameterCount == 1
    } ?: return@afterEvaluate

    val neoforgeRunNames = setOf(
        "BitCam NeoForge Client $mcVersion",
        "BitCam NeoForge Server $mcVersion"
    )

    (runConfigurations as? Iterable<*>)?.toList().orEmpty().forEach { runConfiguration ->
        val runName = runConfiguration?.javaClass
            ?.methods
            ?.firstOrNull { method -> method.name == "getName" && method.parameterCount == 0 }
            ?.invoke(runConfiguration) as? String

        if (runName in neoforgeRunNames) {
            removeMethod.invoke(runConfigurations, runConfiguration)
        }
    }
}
