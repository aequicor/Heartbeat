package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
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
 * - the module registers its impl dependencies in the root `verifyBundleMembership` task, and `check` runs it: every
 *   `…:impl` has exactly one desktop owner, a bundle or the static graph of `:platform-main:di-bundle` (common and JVM);
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
                description = "Fails when an impl module does not belong to exactly one bundle or the foundation."
                impls.set(root.provider { root.subprojects.map { it.path }.filter { it.endsWith(":impl") }.sorted() })
                foundation.set(
                    root.provider {
                        root.findProject(FOUNDATION_PROJECT)?.desktopProjectDependencyPaths()
                            ?.filter { it.endsWith(":impl") }
                            .orEmpty()
                    },
                )
            }
        }
        verify.configure { membership.put(bundlePath, implDependencies()) }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }

        val bundleVersion = providers.gradleProperty(BUNDLE_VERSION_PROPERTY).orElse(provider { version.toString() })
        val assemble = registerAssembleBundle(bundleVersion)
        val bundleId = name
        registerTrainTasks(root).first.configure {
            versions.put(bundleId, bundleVersion)
            jars.from(assemble)
        }
    }

    /**
     * `assembleBundle`: one reproducible jar with the bundle's own classes and the classes of its direct `…:impl`
     * dependencies. Entries are sorted, timestamps and permissions are fixed, so equal sources give equal bytes.
     */
    private fun Project.registerAssembleBundle(bundleVersion: Provider<String>): TaskProvider<Jar> {
        val implPaths = implDependencies()
        val implClasses = configurations.getByName("jvmRuntimeClasspath").incoming.artifactView {
            componentFilter { id -> id is ProjectComponentIdentifier && id.projectPath in implPaths.get() }
        }.files
        val ownClasses = extensions.getByType<KotlinMultiplatformExtension>()
            .targets.getByName("jvm").compilations.getByName("main").output.allOutputs
        val bundleId = name
        return tasks.register<Jar>(ASSEMBLE_TASK) {
            group = "build"
            description = "Assembles the reproducible jar of bundle $bundleId."
            archiveFileName.set(bundleVersion.map { bundleJarName(bundleId, it) })
            destinationDirectory.set(layout.buildDirectory.dir("bundle"))
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            duplicatesStrategy = DuplicatesStrategy.FAIL
            filePermissions { unix(JAR_FILE_MODE) }
            dirPermissions { unix(JAR_DIR_MODE) }
            exclude("META-INF/MANIFEST.MF", "module-info.class")
            dependsOn(implClasses)
            from(ownClasses)
            from({ implClasses.files.map { zipTree(it) } })
        }
    }

    /** Root tasks `bundleManifest` and `affectedBundles`, registered once by the first bundle project. */
    private fun Project.registerTrainTasks(
        root: Project,
    ): Pair<TaskProvider<BundleManifest>, TaskProvider<AffectedBundlesTask>> {
        val bundlePath = path
        val manifest = if (MANIFEST_TASK in root.tasks.names) {
            root.tasks.named<BundleManifest>(MANIFEST_TASK)
        } else {
            root.tasks.register<BundleManifest>(MANIFEST_TASK) {
                group = "build"
                description = "Writes the JSON manifest of the bundle train: versions, jars, SHA-256, platformApi."
                platformApi.set(
                    providers.gradleProperty(PLATFORM_API_PROPERTY).map(String::toInt).orElse(DEFAULT_PLATFORM_API),
                )
                manifestFile.set(root.layout.buildDirectory.file("bundles/manifest.json"))
                // prebuilt `foundation` and `contracts` jars of the train:
                // -Pheartbeat.baseArtifacts=foundation,contracts, jars in -Pheartbeat.baseArtifactDir=<dir>
                val baseVersion = providers.gradleProperty(BUNDLE_VERSION_PROPERTY)
                    .orElse(provider { version.toString() })
                baseArtifacts.putAll(
                    providers.gradleProperty(BASE_ARTIFACTS_PROPERTY).zip(baseVersion) { ids, version ->
                        ids.split(',').map(String::trim).filter(String::isNotEmpty).associateWith { version }
                    }.orElse(emptyMap()), // putAll of a provider without a value leaves the property unset
                )
                jars.from(root.baseArtifactJars())
            }
        }
        val affected = if (AFFECTED_TASK in root.tasks.names) {
            root.tasks.named<AffectedBundlesTask>(AFFECTED_TASK)
        } else {
            root.tasks.register<AffectedBundlesTask>(AFFECTED_TASK) {
                group = "build"
                description = "Lists bundles to rebuild for -P$CHANGED_PATHS_PROPERTY=a,b or -P$CHANGED_FILE_PROPERTY=<file>."
                projectDirs.set(
                    root.provider {
                        root.subprojects.associate { sub ->
                            sub.path to sub.projectDir.relativeTo(root.projectDir).invariantSeparatorsPath
                        }
                    },
                )
                projectDependencies.set(
                    root.provider { root.subprojects.associate { it.path to it.projectDependencyPaths() } },
                )
                // addAll of a provider without a value leaves the whole property unset: each source falls back to an
                // empty list, so that giving only one of the two properties (CI passes only changedFile) still works.
                changedPaths.addAll(
                    providers.gradleProperty(CHANGED_PATHS_PROPERTY).map { value -> value.split(',') }
                        .orElse(emptyList()),
                )
                changedPaths.addAll(
                    providers.gradleProperty(CHANGED_FILE_PROPERTY).flatMap { file ->
                        providers.fileContents(root.layout.projectDirectory.file(file)).asText.map { it.lines() }
                    }.orElse(emptyList()),
                )
                resultFile.set(root.layout.buildDirectory.file("bundles/affected.txt"))
            }
        }
        affected.configure { bundles.add(bundlePath) }
        return manifest to affected
    }

    private fun Project.projectDependencyPaths(): List<String> = configurations
        .filter { !it.name.startsWith(KGP_SWIFTPM_CONFIGURATION_PREFIX) }
        .flatMap { configuration -> configuration.dependencies.withType<ProjectDependency>().map { it.path } }
        .distinct()
        .sorted()

    /** Project dependencies the desktop static graph compiles in (see [isFoundationOwnerConfiguration]). */
    private fun Project.desktopProjectDependencyPaths(): List<String> = configurations
        .filter { configuration -> isFoundationOwnerConfiguration(configuration.name) }
        .flatMap { configuration -> configuration.dependencies.withType<ProjectDependency>().map { it.path } }
        .distinct()
        .sorted()

    private fun Project.implDependencies() = provider { projectDependencyPaths().filter { it.endsWith(":impl") } }

    private companion object {
        const val JDK_VERSION = 17
        const val VERIFY_TASK = "verifyBundleMembership"
        const val FOUNDATION_PROJECT = ":platform-main:di-bundle"
        const val ASSEMBLE_TASK = "assembleBundle"
        const val MANIFEST_TASK = "bundleManifest"
        const val AFFECTED_TASK = "affectedBundles"
        const val BUNDLE_VERSION_PROPERTY = "heartbeat.bundleVersion"
        const val BASE_ARTIFACTS_PROPERTY = "heartbeat.baseArtifacts"
        const val BASE_ARTIFACT_DIR_PROPERTY = "heartbeat.baseArtifactDir"
        const val PLATFORM_API_PROPERTY = "heartbeat.platformApi"
        const val CHANGED_PATHS_PROPERTY = "heartbeat.changedPaths"
        const val CHANGED_FILE_PROPERTY = "heartbeat.changedFile"
        const val DEFAULT_PLATFORM_API = 1
        const val JAR_FILE_MODE = "rw-r--r--"
        const val JAR_DIR_MODE = "rwxr-xr-x"
    }
}

/**
 * Prebuilt base jars of `-Pheartbeat.baseArtifactDir=<dir>`; an absent property is an empty collection, because a
 * provider without a value fails the resolution of the task graph of every build that does not pass it.
 */
internal fun Project.baseArtifactJars(): Provider<Any> =
    providers.gradleProperty("heartbeat.baseArtifactDir")
        .map<Any> { dir -> fileTree(dir) { include("*.jar") } }
        .orElse(emptyList<Any>())
