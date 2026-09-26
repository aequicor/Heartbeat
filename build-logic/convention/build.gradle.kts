plugins {
    `kotlin-dsl`
}

group = "io.aequicor.heartbeat.buildlogic"

dependencies {
    // compileOnly: сам плагин кладётся на classpath сборки через `apply false` в корневом build.gradle.kts.
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("heartbeatDetekt") {
            id = libs.plugins.heartbeat.detekt.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.DetektConventionPlugin"
        }
    }
}
