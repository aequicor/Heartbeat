plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Единственный модуль, который видит все impl: здесь Metro собирает контрибуции и генерирует граф.
// Граф объявляется per-platform (androidMain/jvmMain/iosMain), чтобы видеть платформенные контрибуции.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.common)
            api(projects.core.di.api)
            api(projects.core.profileFacade.api)
            api(projects.core.navigation.api)
            implementation(projects.core.logging)
            implementation(projects.core.di.impl)
            implementation(projects.core.profileFacade.impl)
            implementation(projects.core.navigation.impl)
        }
        jvmTest.dependencies {
            implementation(projects.core.di.ext)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
