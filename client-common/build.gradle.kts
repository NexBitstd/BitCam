import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

val targetJavaVersion = (rootProject.property("java_version") as String).toInt()
val baseArchiveName = rootProject.property("archives_base_name") as String
val bytedecoOpenCvVersion = rootProject.property("bytedeco_opencv_version") as String
val bytedecoOpenBlasVersion = rootProject.property("bytedeco_openblas_version") as String
val javah264Version = rootProject.property("javah264_version") as String

plugins {
    `java-library`
}

base {
    archivesName.set("${baseArchiveName}-client-common")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

dependencies {
    api(project(":common"))
    api(project(":protocol"))
    compileOnly("com.github.sarxos:webcam-capture:0.3.12")
    compileOnly("org.bytedeco:openblas:$bytedecoOpenBlasVersion")
    compileOnly("org.bytedeco:opencv:$bytedecoOpenCvVersion")
    compileOnly("dev.nexbit:javah264:$javah264Version")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE.txt")) {
        rename { "${it}_${baseArchiveName}" }
    }
}
