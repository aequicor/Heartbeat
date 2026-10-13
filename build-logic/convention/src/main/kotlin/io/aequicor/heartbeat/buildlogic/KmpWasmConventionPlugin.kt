package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `heartbeat.kmp.wasm` — adds the `wasmJs` browser target to a module already built by `heartbeat.kmp.library`
 * (or `heartbeat.kmp.compose`). Only the side panel of the Chromium extension runs on Kotlin/Wasm, so the target
 * goes to `:chromium-plugin:panel` and the modules it compiles, not to every library.
 *
 * Wasm modules share `commonTest`, which runs on the JVM (`jvmTest`). `check` still compiles the tests for wasm,
 * but the browser test task is off and detached from Node.js, Yarn and npm: Karma would need a local Chrome and
 * the network on every machine and runner.
 */
class KmpWasmConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        extensions.configure<KotlinMultiplatformExtension> {
            @OptIn(ExperimentalWasmDsl::class)
            wasmJs {
                browser {
                    testTask {
                        enabled = false
                        setDependsOn(emptyList<Any>())
                    }
                }
            }
        }
    }
}
