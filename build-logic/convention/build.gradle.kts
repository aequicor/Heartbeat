plugins {
    `kotlin-dsl`
}

group = "io.aequicor.heartbeat.buildlogic"

dependencies {
    // compileOnly: сами плагины кладутся на classpath сборки через `apply false` в корневом build.gradle.kts.
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.metro.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("heartbeatDetekt") {
            id = libs.plugins.heartbeat.detekt.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.DetektConventionPlugin"
        }
        register("heartbeatKmpLibrary") {
            id = libs.plugins.heartbeat.kmp.library.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.KmpLibraryConventionPlugin"
        }
        register("heartbeatKmpCompose") {
            id = libs.plugins.heartbeat.kmp.compose.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.KmpComposeConventionPlugin"
        }
        register("heartbeatMetro") {
            id = libs.plugins.heartbeat.metro.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.MetroConventionPlugin"
        }
        register("heartbeatRoom") {
            id = libs.plugins.heartbeat.room.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.RoomConventionPlugin"
        }
    }
}
