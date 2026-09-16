import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.bundling.Zip

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
}

group = "dev.musicvisualizer"
version = "0.1.7"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("net.java.dev.jna:jna-jpms:5.19.1")
    implementation("net.java.dev.jna:jna-platform-jpms:5.19.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        mainClass = "dev.musicvisualizer.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            modules("java.net.http", "java.instrument", "jdk.unsupported")
            packageName = "YouTubeMusicVisualizer"
            packageVersion = "0.1.7"
            description = "Minimal YouTube Music audio visualizer"
            vendor = "Personal"
            windows {
                console = false
                menuGroup = "YouTube Music Visualizer"
                shortcut = true
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Zip>("portableZip") {
    group = "distribution"
    description = "Creates a portable Windows ZIP with the bundled runtime."
    dependsOn("verifyDistributableRuntime")
    from(layout.buildDirectory.dir("compose/binaries/main/app/YouTubeMusicVisualizer"))
    archiveFileName.set("YouTubeMusicVisualizer-${project.version}-windows.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

tasks.register("verifyDistributableRuntime") {
    group = "verification"
    description = "Verifies that the bundled runtime contains every module used by the application."
    dependsOn("createDistributable")
    doLast {
        val releaseFile = layout.buildDirectory
            .file("compose/binaries/main/app/YouTubeMusicVisualizer/runtime/release")
            .get().asFile
        val release = releaseFile.readText()
        val requiredModules = setOf("java.net.http", "java.instrument", "jdk.unsupported")
        val missing = requiredModules.filterNot { module ->
            Regex("(?:^|[ \\\"])${Regex.escape(module)}(?:[ \\\"]|$)").containsMatchIn(release)
        }
        check(missing.isEmpty()) {
            "Bundled runtime is missing required Java modules: ${missing.joinToString()}"
        }
    }
}
