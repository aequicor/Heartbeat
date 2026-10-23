package io.aequicor.heartbeat.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.withType

private const val BUNDLE_PROJECT = ":platform-main:di-bundle"
private const val BUNDLES_PREFIX = ":bundles:"
private const val PLATFORM_PREFIX = ":platform-main:"
private const val FEATURES_PREFIX = ":features:"
private const val BUNDLE_REGISTRY_PREFIX = ":core:bundles:"
private const val MACHINE_STORE_EXT = ":core:state-machine:flowmvi-ext"

/**
 * Prefix of the Kotlin Gradle plugin's SwiftPM import tooling configurations (KGP 2.4+). For `Package.resolved`
 * aggregation the first project with Apple targets gets project dependencies on *every* other one
 * (`swiftPMDependenciesForLockFilesMetadataClasspathDependencies`), `…:impl` included. These only carry SwiftPM
 * metadata JSON, never code, so they are not module dependencies in the sense of the boundary rule.
 */
internal const val KGP_SWIFTPM_CONFIGURATION_PREFIX = "swiftPMDependencies"

/**
 * Module boundaries, checked on the Gradle configuration (fails it). Applied from `heartbeat.detekt`, which every
 * module uses (app modules included):
 * - `…:impl` modules — of features and of `core` — are wired into the app only through [BUNDLE_PROJECT] and the
 *   desktop bundle assemblers (`:bundles:<name>`); nobody else may depend on them;
 * - a bundle never depends on another bundle (they meet only in the runtime registry), and only `platform-main`
 *   modules may depend on bundles, and feature modules never see `:core:bundles:*`;
 * - `…:api` modules stay free of FlowMVI: no dependency on [MACHINE_STORE_EXT].
 */
internal fun Project.checkImplDependencies() {
    if (path == BUNDLE_PROJECT) return
    val self = path
    val isBundle = self.startsWith(BUNDLES_PREFIX)
    configurations.matching { !it.name.startsWith(KGP_SWIFTPM_CONFIGURATION_PREFIX) }.configureEach {
        dependencies.withType<ProjectDependency>().configureEach {
            val target = this.path
            if (target.endsWith(":impl") && !isBundle) {
                throw GradleException(
                    "$self must not depend on $target: impl modules are wired only in $BUNDLE_PROJECT " +
                        "and in the bundle assemblers ($BUNDLES_PREFIX*). Depend on the matching api module instead.",
                )
            }
            if (target.startsWith(BUNDLES_PREFIX) && !self.startsWith(PLATFORM_PREFIX)) {
                throw GradleException(
                    "$self must not depend on $target: bundles are assembled into the app only by platform-main. " +
                        "Bundles reach each other through the runtime registry, not through Gradle.",
                )
            }
            if (self.startsWith(FEATURES_PREFIX) && target.startsWith(BUNDLE_REGISTRY_PREFIX)) {
                throw GradleException(
                    "$self must not depend on $target: the bundle registry lives on the bundle boundary, " +
                        "feature code stays unaware of bundles.",
                )
            }
            if (self.endsWith(":api") && target == MACHINE_STORE_EXT) {
                throw GradleException(
                    "$self must not depend on $target: api modules describe machines, stores live in impl.",
                )
            }
        }
    }
}
