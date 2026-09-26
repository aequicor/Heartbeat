plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

// Compose-рендер навигации: NavStack, NavPanels, анимации переходов, shared-element «раскрытие из превью».
// Используют impl фич и точки входа platform-main.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.navigation.api)
            api(libs.compose.ui)
            api(libs.decompose.extensionsCompose)
            api(libs.decompose.extensionsComposeExperimental)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)
        }
    }
}
