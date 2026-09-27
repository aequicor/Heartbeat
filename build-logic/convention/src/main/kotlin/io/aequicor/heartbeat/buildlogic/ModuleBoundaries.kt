package io.aequicor.heartbeat.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.withType

private const val BUNDLE_PROJECT = ":platform-main:di-bundle"
private const val MACHINE_STORE_EXT = ":core:state-machine:flowmvi-ext"

/**
 * Prefix of the Kotlin Gradle plugin's SwiftPM import tooling configurations (KGP 2.4+). For `Package.resolved`
 * aggregation the first project with Apple targets gets project dependencies on *every* other one
 * (`swiftPMDependenciesForLockFilesMetadataClasspathDependencies`), `…:impl` included. These only carry SwiftPM
 * metadata JSON, never code, so they are not module dependencies in the sense of the boundary rule.
 */
private const val KGP_SWIFTPM_CONFIGURATION_PREFIX = "swiftPMDependencies"

/**
 * Module boundaries, checked on the Gradle configuration (fails it). Applied from `heartbeat.detekt`, which every
 * module uses (app modules included):
 * - `…:impl` modules — of features and of `core` — are wired into the app only through [BUNDLE_PROJECT]; nobody
 *   else may depend on them (docs/adr/0002-di-scopes.md);
 * - `…:api` modules stay free of FlowMVI: no dependency on [MACHINE_STORE_EXT] (docs/adr/0004-state-machine.md).
 */
internal fun Project.checkImplDependencies() {
    if (path == BUNDLE_PROJECT) return
    val self = path
    configurations.matching { !it.name.startsWith(KGP_SWIFTPM_CONFIGURATION_PREFIX) }.configureEach {
        dependencies.withType<ProjectDependency>().configureEach {
            val target = this.path
            if (target.endsWith(":impl")) {
                throw GradleException(
                    "$self must not depend on $target: impl modules are wired only in $BUNDLE_PROJECT. " +
                        "Depend on the matching api module instead.",
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
