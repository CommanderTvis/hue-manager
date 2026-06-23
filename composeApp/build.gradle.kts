import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(17)

    androidLibrary {
        namespace = "io.github.commandertvis.huemanager"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.composeUiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.kmpClient.okhttp)
        }

        commonMain.dependencies {
            implementation(libs.composeRuntime)
            implementation(libs.composeFoundation)
            implementation(libs.composeMaterial3)
            implementation(libs.composeUi)
            implementation(libs.composeComponentsResources)
            implementation(libs.composeUiToolingPreview)
            implementation(libs.composeMaterialIconsExtended)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(projects.shared)

            implementation(libs.ktor.kmpClient.core)
            implementation(libs.ktor.kmpClient.contentNegotiation)
            implementation(libs.ktor.kmpSerialization.json)

            implementation(libs.kotlinx.serialization.json)

            implementation(libs.colorpicker.compose)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.kmpClient.cio)
            implementation(libs.logback)
        }
    }
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    group = "build"
    description = "Generates BuildInfo.kt with the current git short commit hash (BUILD_COMMIT)."
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    val rootDir = rootProject.layout.projectDirectory.asFile
    outputs.dir(outputDir)

    doLast {
        // Prefer the BUILD_COMMIT env var (set in Docker/CI where git isn't available);
        // fall back to `git`, then to "unknown" so the build never hard-depends on git.
        val gitCommit = System.getenv("BUILD_COMMIT")?.trim()?.takeIf { it.isNotEmpty() }?.take(7)
            ?: runCatching {
                ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(rootDir)
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().readText().trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "unknown"

        val file = outputDir.get().file(
            "io/github/commandertvis/huemanager/BuildInfo.kt"
        ).asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.github.commandertvis.huemanager

            const val BUILD_COMMIT: String = "$gitCommit"
            """.trimIndent() + "\n"
        )
    }
}

kotlin.sourceSets.commonMain {
    kotlin.srcDir(generateBuildInfo.map { it.outputs.files.singleFile })
}

compose.desktop.application {
    mainClass = "io.github.commandertvis.huemanager.MainKt"
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
        packageName = "Hue Manager"
        packageVersion = "1.0.0"
        modules("java.naming")

        macOS {
            bundleID = "io.github.commandertvis.huemanager"
        }
    }
}
