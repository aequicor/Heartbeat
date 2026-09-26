package io.aequicor.heartbeat.buildlogic

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * `heartbeat.detekt` — detekt 2 with the project-wide config, ktlint wrapper, compose-rules
 * and the custom Heartbeat rule set (`:lint:detekt-rules`: logging and error-handling policy).
 *
 * Tasks:
 * - `detekt` — fast plain analysis of every source set under `src/` (no type resolution);
 * - `detekt<Compilation>` (e.g. `detektJvmMain`, `detektMain`) — with type resolution,
 *   enables rules that need the Analysis API (e.g. `SuspendFunSwallowedCancellation`).
 */
class DetektConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("dev.detekt")
        // every module applies detekt — the natural carrier for project-wide module boundary checks
        checkImplDependencies()

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<DetektExtension> {
            toolVersion.set(libs.findVersion("detekt").get().requiredVersion)
            buildUponDefaultConfig.set(true)
            parallel.set(true)
            config.setFrom(rootDir.resolve(CONFIG_PATH))
            // KMP keeps sources in src/<sourceSet>/kotlin — the plugin default (src/main/kotlin) misses them.
            source.setFrom(layout.projectDirectory.dir("src"))
        }

        dependencies.add(DETEKT_PLUGINS, libs.findLibrary("detekt-rules-ktlint").get())
        dependencies.add(DETEKT_PLUGINS, libs.findLibrary("detekt-rules-compose").get())
        // Also applied to :lint:detekt-rules itself — the rules check their own sources.
        // Non-transitive: the rules jar must not drag its own Kotlin stdlib onto detekt's classpath.
        val rules = dependencies.project(mapOf("path" to RULES_PROJECT)) as ModuleDependency
        rules.isTransitive = false
        dependencies.add(DETEKT_PLUGINS, rules)

        tasks.withType<Detekt>().configureEach {
            exclude("**/build/**", "**/generated/**")
            reports {
                html.required.set(true)
                sarif.required.set(true)
                checkstyle.required.set(false)
                markdown.required.set(false)
            }
        }
    }

    private companion object {
        const val DETEKT_PLUGINS = "detektPlugins"
        const val RULES_PROJECT = ":lint:detekt-rules"
        const val CONFIG_PATH = "config/detekt/detekt.yml"
    }
}
