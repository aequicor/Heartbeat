plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization) // @Serializable test DTOs
}

// HttpClient приложения: engine per-platform, JSON, таймауты, ретраи идемпотентных запросов, логи NET.
// Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.network.api)
            implementation(projects.core.di.api)
            implementation(projects.core.logging)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
        }
        // Compile the same OkHttp adapter on JVM and Android without changing the KMP hierarchy.
        androidMain { kotlin.srcDir("src/okhttpMain/kotlin") }
        jvmMain { kotlin.srcDir("src/okhttpMain/kotlin") }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
