import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.heartbeat.detekt)
}

kotlin {
    jvmToolchain(libs.versions.desktop.jdk.get().toInt())
}

dependencies {
    implementation(projects.designSystem.catalog)
    implementation(projects.designSystem.adaptive)
    implementation(projects.designSystem.tokens)
    implementation(projects.core.logging)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    testImplementation(libs.kotlin.testJunit)
}

compose.desktop {
    application {
        mainClass = "io.aequicor.heartbeat.platform.uikitsandbox.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            // suggestModules identifies these runtime dependencies; Compose's default image omits them.
            modules("java.instrument", "jdk.unsupported")
            packageName = "Aequicor UIKit Sandbox"
            packageVersion = "1.0.0"
        }
    }
}
