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
 * but the browser test task is off and detached from executable linking, Node.js, Yarn and npm: Karma would
 * need a local Chrome and the network on every machine and runner. Compose's browser executable check is also
 * disabled because these libraries only compile tests; they do not run Compose UI in a browser.
 */
class KmpWasmConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        rootProject.pluginManager.apply(WindowsNpmInstallConventionPlugin::class.java)
        configureBinaryenWindowsArm64(
            providers.systemProperty("os.name").get(),
            providers.systemProperty("os.arch").get(),
        )
        extensions.configure<KotlinMultiplatformExtension> {
            @OptIn(ExperimentalWasmDsl::class)
            wasmJs {
                val testCompilation = compilations.getByName("test")
                browser {
                    testTask {
                        enabled = false
                        setDependsOn(listOf(testCompilation.compileTaskProvider))
                        // This input carries an implicit dependency on the test executable's linkSync task.
                        // Clearing dependsOn alone still links a runner that will never execute.
                        inputFileProperty.unset()
                    }
                }
            }
        }
        // Compose adds this task and its browserTest dependency in afterEvaluate, after testTask above.
        tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach {
            enabled = false
        }
    }
}
