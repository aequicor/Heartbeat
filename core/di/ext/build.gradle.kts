plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

// Связка скоупов с жизненным циклом компонентов (Essenty InstanceKeeper/StateKeeper).
// Потребители — impl фич и platform-main; core:mvi / core:navigation от ext не зависят.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.di.api)
            api(libs.essenty.instanceKeeper)
            api(libs.essenty.stateKeeper)
            implementation(projects.core.logging)
        }
    }
}
