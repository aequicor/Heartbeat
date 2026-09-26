package io.aequicor.heartbeat.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `heartbeat.kmp.library` — a Kotlin Multiplatform library for every Heartbeat platform:
 * `android`, `jvm` (desktop macOS/Windows), `iosArm64`, `iosSimulatorArm64`; JDK 17 toolchain; detekt.
 *
 * - Android namespace is derived from the Gradle path: `:core:di:api` → `io.aequicor.heartbeat.core.di.api`.
 * - `explicitApi()` for `core` modules and every `api` module — their public surface is a contract.
 * - `commonTest` gets kotlin-test and coroutines-test.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply("heartbeat.detekt")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(JDK_VERSION)

            jvm()
            iosArm64()
            iosSimulatorArm64()
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = namespaceFor(path)
                compileSdk = libs.intVersion("android-compileSdk")
                minSdk = libs.intVersion("android-minSdk")
            }

            if (path.startsWith(":core:") || path.endsWith(":api")) explicitApi()

            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
                implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }
    }

    private fun VersionCatalog.intVersion(alias: String): Int = findVersion(alias).get().requiredVersion.toInt()

    /** Mirrors the package convention: `:platform-main:di-bundle` → `io.aequicor.heartbeat.platform.dibundle`. */
    private fun namespaceFor(path: String): String =
        "io.aequicor.heartbeat." + path.removePrefix(":").split(':')
            .joinToString(".") { segment -> GROUP_PACKAGES[segment] ?: segment.replace("-", "") }

    private companion object {
        const val JDK_VERSION = 17
        val GROUP_PACKAGES = mapOf("platform-main" to "platform", "design-system" to "ds", "features" to "feature")
    }
}
