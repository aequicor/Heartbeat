package io.aequicor.heartbeat.buildlogic

import dev.zacsweers.metro.gradle.MetroPluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `heartbeat.metro` — Metro DI compiler plugin (runtime is added by the plugin itself).
 *
 * `generateContributionProviders`: contributions are exposed through generated `@Provides`
 * functions that return only the bound type, so implementations stay `internal` across modules.
 * Must be the same in every module — hence it lives here, not in module build scripts.
 */
class MetroConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("dev.zacsweers.metro")

        extensions.configure<MetroPluginExtension> {
            generateContributionProviders.set(true)
        }
    }
}
