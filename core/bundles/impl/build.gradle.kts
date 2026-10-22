plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Рантайм-реестр бандлов (этап 1 поставки desktop бандлами). Пока — только spike поведения Metro 1.4.5 в jvmTest.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.di.api)
            implementation(projects.core.common)
        }
        jvmTest.dependencies {
            implementation(projects.core.profileFacade.api)
        }
    }
}
