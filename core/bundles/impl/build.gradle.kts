plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Рантайм-реестр бандлов (этап 1 поставки desktop бандлами). Хост (`BundleHost`) — jvmMain; spike поведения Metro 1.4.5 — в jvmTest.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.bundles.api)
            implementation(projects.core.logging)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
        }
        jvmMain.dependencies {
            implementation(projects.core.profileFacade.api)
        }
    }
}
