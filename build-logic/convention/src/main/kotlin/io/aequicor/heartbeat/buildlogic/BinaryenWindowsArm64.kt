package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin

/**
 * Kotlin 2.4.20 requests `aarch64-windows`, but Binaryen publishes `arm64-windows` archives.
 * Override only the archive pattern on Windows ARM64, retaining Kotlin's version, download URL,
 * extraction and cache handling. Remove this workaround when Kotlin selects the published archive.
 */
@OptIn(ExperimentalWasmDsl::class)
internal fun Project.configureBinaryenWindowsArm64(osName: String, osArch: String) {
    if (!osName.contains("windows", ignoreCase = true) || osArch.lowercase() !in setOf("aarch64", "arm64")) return

    plugins.withType<BinaryenPlugin> {
        // Build scripts configure the environment after applying the convention plugin.
        afterEvaluate {
            val binaryen = extensions.getByType<BinaryenEnvSpec>()
            if (binaryen.downloadBaseUrl.isPresent) {
                repositories.ivy {
                    name = "Binaryen Windows ARM64"
                    setUrl(binaryen.downloadBaseUrl)
                    isAllowInsecureProtocol = binaryen.allowInsecureProtocol.get()
                    patternLayout {
                        artifact("version_[revision]/binaryen-version_[revision]-arm64-windows.[ext]")
                    }
                    metadataSources { artifact() }
                    content { includeModule("com.github.webassembly", "binaryen") }
                }
            }
        }
    }
}
