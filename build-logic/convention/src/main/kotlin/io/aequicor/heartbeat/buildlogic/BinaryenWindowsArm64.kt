package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin

/**
 * Kotlin 2.4.20 requests `aarch64-windows`, but Binaryen publishes `arm64-windows` archives.
 * Rewrite only Kotlin's temporary Binaryen repository pattern, retaining its URL, extraction and cache handling.
 * A permanent project repository would hide the dependency repositories declared in settings.
 * Remove this workaround when Kotlin selects the published archive.
 */
@OptIn(ExperimentalWasmDsl::class)
internal fun Project.configureBinaryenWindowsArm64(osName: String, osArch: String) {
    if (!osName.contains("windows", ignoreCase = true) || osArch.lowercase() !in setOf("aarch64", "arm64")) return

    plugins.withType<BinaryenPlugin> {
        val binaryen = extensions.getByType<BinaryenEnvSpec>()
        repositories.withType<IvyArtifactRepository>().configureEach {
            // AbstractSetupTask creates and removes this repository around distribution resolution.
            val downloadUrl = binaryen.downloadBaseUrl.orNull
            if (downloadUrl != null && name == "Distributions at $downloadUrl") {
                patternLayout {
                    artifact("version_[revision]/binaryen-version_[revision]-arm64-windows.[ext]")
                }
            }
        }
    }
}
