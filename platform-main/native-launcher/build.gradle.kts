plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.heartbeat.detekt)
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

