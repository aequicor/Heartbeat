import org.gradle.api.tasks.testing.Test

plugins {
    `kotlin-dsl`
}

group = "io.aequicor.heartbeat.buildlogic"

val functionalTestClasspath by configurations.creating

dependencies {
    // compileOnly: сами плагины кладутся на classpath сборки через `apply false` в корневом build.gradle.kts.
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.metro.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)

    testImplementation(libs.kotlin.gradlePlugin)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(gradleTestKit())
    functionalTestClasspath(libs.kotlin.gradlePlugin)
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalTestClasspath)
}

tasks.withType<Test>().configureEach {
    inputs.property("heartbeat.test.os", providers.systemProperty("os.name"))
    inputs.property("heartbeat.test.arch", providers.systemProperty("os.arch"))
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
        register("heartbeatKmpWasm") {
            id = libs.plugins.heartbeat.kmp.wasm.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.KmpWasmConventionPlugin"
        }
        register("heartbeatMetro") {
            id = libs.plugins.heartbeat.metro.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.MetroConventionPlugin"
        }
        register("heartbeatRoom") {
            id = libs.plugins.heartbeat.room.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.RoomConventionPlugin"
        }
        register("heartbeatBundle") {
            id = libs.plugins.heartbeat.bundle.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.BundleConventionPlugin"
        }
        register("heartbeatTrain") {
            id = libs.plugins.heartbeat.train.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.TrainBaseConventionPlugin"
        }
        register("heartbeatJcef") {
            id = libs.plugins.heartbeat.jcef.get().pluginId
            implementationClass = "io.aequicor.heartbeat.buildlogic.JcefConventionPlugin"
        }
    }
}
