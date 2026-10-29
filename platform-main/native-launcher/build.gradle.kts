import java.util.Base64

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.heartbeat.detekt)
}

// Иконка окна встраивается в exe как base64-константа: лончер не зависит от рабочего каталога
// и показывает иконку даже при запуске вне каталога установки (E2E, dev-прогоны).
val embeddedIconFile = rootProject.file("platform-main/desktop/icons/heartbeat.ico")
val generateEmbeddedIcon = tasks.register("generateEmbeddedIcon") {
    val iconFile = embeddedIconFile
    val outDir = layout.buildDirectory.dir("generated-src/icon")
    inputs.file(iconFile)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        val encoded = Base64.getEncoder().encodeToString(iconFile.readBytes())
        dir.resolve("EmbeddedIcon.kt").writeText(
            """
            package io.aequicor.heartbeat.platform.nativelauncher

            /** Base64 от platform-main/desktop/icons/heartbeat.ico; генерируется задачей generateEmbeddedIcon. */
            internal const val EMBEDDED_ICON_BASE64: String = "$encoded"
            """.trimIndent() + "\n",
        )
    }
}

// Только desktop-хосты нативного лончера: общий код bootstrap-протокола, JVM — для тестов и инструментов.
// Нативные desktop-таргеты (mingwX64, macosX64/macosArm64, linuxX64) добавляются вместе с лончером (Этап 3).
// Это не библиотека «для всех платформ Heartbeat», поэтому heartbeat.kmp.library (android/iOS) не применяется.
kotlin {
    jvmToolchain(17)
    jvm()
    mingwX64 {
        binaries {
            executable {
                baseName = "heartbeat-launcher"
                entryPoint = "io.aequicor.heartbeat.platform.nativelauncher.main"
            }
        }
    }

    sourceSets {
        mingwMain {
            kotlin.srcDir(generateEmbeddedIcon.map { it.outputs.files.single() })
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(projects.core.logging)
        }
        mingwMain.dependencies {
            implementation(libs.ionspin.libsodium)
            implementation(libs.ktor.client.winhttp)
        }
    }
}

