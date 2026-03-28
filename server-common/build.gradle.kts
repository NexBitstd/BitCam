import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

val targetJavaVersion = (rootProject.property("java_version") as String).toInt()
val baseArchiveName = rootProject.property("archives_base_name") as String

plugins {
    `java-library`
}

base {
    archivesName.set("${baseArchiveName}-server-common")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}

dependencies {
    api(project(":common"))
    api(project(":protocol"))
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
