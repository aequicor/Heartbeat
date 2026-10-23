package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * `heartbeat.bundle` — an assembler module of one desktop bundle (`:bundles:<name>`): a JVM-only module that holds
 * the bundle's Metro graph and `BundleEntry`, depends on the `…:impl` modules of its member features, and is
 * published as one unit of delivery.
 *
 * - JDK 17 toolchain, Metro and detekt like every module; `core:bundles:api` for the `BundleEntry` contract;
 * - the module registers its impl dependencies in the root `verifyBundleMembership` task, and `check` runs it;
 * - boundary rules (no bundle-to-bundle dependency, only `platform-main` consumes bundles) live in
 *   [checkImplDependencies].
 */
class BundleConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("heartbeat.metro")
        pluginManager.apply("heartbeat.detekt")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(JDK_VERSION)
            jvm()
            sourceSets.getByName("jvmMain").dependencies {
                api(project(":core:bundles:api"))
            }
            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
            }
        }

        val bundlePath = path
        val root = rootProject
        val verify = if (VERIFY_TASK in root.tasks.names) {
            root.tasks.named<VerifyBundleMembership>(VERIFY_TASK)
        } else {
            root.tasks.register<VerifyBundleMembership>(VERIFY_TASK) {
                group = "verification"
                description = "Fails when an impl module belongs to more than one bundle."
            }
        }
        verify.configure { membership.put(bundlePath, implDependencies()) }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }

    private fun Project.implDependencies() = provider {
        configurations
            .filter { !it.name.startsWith(KGP_SWIFTPM_CONFIGURATION_PREFIX) }
            .flatMap { configuration -> configuration.dependencies.withType<ProjectDependency>().map { it.path } }
            .filter { it.endsWith(":impl") }
            .distinct()
            .sorted()
    }

    private companion object {
        const val JDK_VERSION = 17
        const val VERIFY_TASK = "verifyBundleMembership"
    }
}
