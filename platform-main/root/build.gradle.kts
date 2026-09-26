plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

// Compose-корень платформы: рисует HeartbeatRoot (гостевое дерево / дерево профиля) с shared-переходами.
// Подключают точки входа android / desktop / shared (iOS).
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.platformMain.diBundle)
            api(projects.core.navigation.compose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)
        }
    }
}
