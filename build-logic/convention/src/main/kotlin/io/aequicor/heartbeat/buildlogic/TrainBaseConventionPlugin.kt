package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File

/** Which unit of delivery a resolved runtime artifact of the desktop app belongs to. */
internal enum class TrainPart {
    /** Project code of `:core`, the design system, the static graph and the platform entry points. */
    Foundation,

    /** The `:features:*:api` modules. */
    Contracts,

    /** The platform launcher: the train carries its jar, the launcher selector installs it into a slot. */
    Launcher,

    /** A `:bundles:*` assembler or an `…:impl` module that a bundle jar carries. */
    Bundle,

    /** A third-party library: it ships with the installer, because its natives depend on the OS and architecture. */
    Platform,
}

/**
 * The launcher selector starts the launcher and the train, so it stays on the installer's class path. The launcher
 * itself is an artifact of the train, kept in the installer only as a file (the fallback of the selector).
 */
private const val SELECTOR_PROJECT = ":platform-main:launcher-selector"
internal const val LAUNCHER_PROJECT = ":platform-main:launcher"

private val FEATURE_API = Regex("""^:features:.+:api$""")

/**
 * Part of an artifact: [projectPath] is the Gradle path of a project artifact and null for a third-party one;
 * [bundleImpls] are the `…:impl` modules that bundle jars carry.
 */
internal fun trainPartOf(projectPath: String?, bundleImpls: Set<String>): TrainPart = when {
    projectPath == null || projectPath == SELECTOR_PROJECT -> TrainPart.Platform
    projectPath == LAUNCHER_PROJECT -> TrainPart.Launcher
    projectPath.startsWith(":bundles:") || projectPath in bundleImpls -> TrainPart.Bundle
    FEATURE_API.matches(projectPath) -> TrainPart.Contracts
    else -> TrainPart.Foundation
}

/** Jars of the third-party libraries of the desktop app: the part of the app that stays in the installer. */
interface TrainPlatformJars {
    /** Third-party runtime jars, without any project code. */
    val jars: Provider<List<File>>

    /**
     * [jars] by the file name they get in the installer: two libraries may publish a jar of the same name (the
     * AndroidX and JetBrains builds of one Compose module), then the group id is put in front of the file name.
     */
    val installedNames: Provider<Map<String, File>>
}

/** Unique file names for [artifacts] given as `group to file`; only a name that occurs twice is prefixed. */
internal fun uniqueJarNames(artifacts: List<Pair<String?, File>>): Map<String, File> {
    val counts = artifacts.groupingBy { it.second.name }.eachCount()
    return artifacts.associate { (group, file) ->
        val name = if (counts.getValue(file.name) > 1 && group != null) "$group-${file.name}" else file.name
        name to file
    }.also { check(it.size == artifacts.size) { "Platform jars have duplicate names: ${counts.filterValues { c -> c > 1 }}" } }
}

/**
 * `heartbeat.train` — the desktop application module (`:platform-main:desktop`) splits its runtime classpath into the
 * parts of the bundle train: `assembleFoundation` (project code outside bundles and contracts), `assembleContracts`
 * (the `:features:*:api` modules) and `assembleLauncher` (the platform launcher) are reproducible jars named like bundle
 * jars and listed as base artifacts in the root `bundleManifest`; the launcher entry is signed there. Third-party
 * libraries are not part of the train ([TrainPlatformJars]).
 * The `heartbeatTrain { sdk(...) }` extension ([TrainSdkExtension]) declares the foundation modules bundles may compile
 * against; the root `verifyTrainSdk` task checks it and runs with `check` here and in every bundle.
 * With `-Pheartbeat.trainShrink=shrink` `foundation` is processed by ProGuard (`shrinkFoundation`) after it is assembled
 * ([ShrinkTrainJar]); `contracts` never is.
 */
class TrainBaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        val sdk = extensions.create("heartbeatTrain", TrainSdkExtension::class.java)
        registerVerifyTrainSdk(sdk)
        registerGenerateTrainSdkRules(sdk)
        tasks.matching { it.name == "check" }.configureEach { dependsOn(rootProject.tasks.named(VERIFY_TRAIN_SDK_TASK)) }
        val version = providers.gradleProperty(VERSION_PROPERTY).orElse(provider { project.version.toString() })
        val artifacts = configurations.named("runtimeClasspath").map { configuration ->
            configuration.incoming.artifacts.resolvedArtifacts.get().toList()
        }
        val bundleImpls = provider { rootProject.bundleImplPaths() }
        fun jarsOf(part: TrainPart): Provider<List<File>> = artifacts.zip(bundleImpls) { all, impls ->
            all.filter { trainPartOf(it.projectPath(), impls) == part }.map { it.file }
        }
        val platform = artifacts.zip(bundleImpls) { all, impls ->
            all.filter { trainPartOf(it.projectPath(), impls) == TrainPart.Platform }
        }
        extensions.add(TrainPlatformJars::class.java, "trainPlatform", object : TrainPlatformJars {
            override val jars = jarsOf(TrainPart.Platform)
            override val installedNames = platform.map { all ->
                uniqueJarNames(all.map { ((it.id.componentIdentifier as? ModuleComponentIdentifier)?.group) to it.file })
            }
        })
        val ownClasses = extensions.getByType<SourceSetContainer>().getByName("main").output
        val mode = TrainShrinkMode.parse(providers.gradleProperty(TrainShrinkMode.PROPERTY).orNull)
        if (mode == TrainShrinkMode.Obfuscate) {
            val bundles = rootProject.subprojects.filter { it.parent?.path == ":bundles" }.map { it.name }
            checkObfuscationTags(listOf("foundation") + bundles)
        }
        val foundationDirectory = if (mode == TrainShrinkMode.Off) FINAL_JAR_DIRECTORY else RAW_JAR_DIRECTORY
        val foundation = registerBaseJar(
            "foundation",
            version,
            jarsOf(TrainPart.Foundation),
            ownClasses,
            foundationDirectory,
            if (mode == TrainShrinkMode.Off) "assembleFoundation" else "assembleFoundationRaw",
        )
        // Contracts are the interface between processed jars and are used as assembled.
        val contracts = registerBaseJar("contracts", version, jarsOf(TrainPart.Contracts), null, FINAL_JAR_DIRECTORY, "assembleContracts")
        // The jar the manifest and the installer take: the processed foundation when the train is shrunk.
        val foundationJar: Any = if (mode == TrainShrinkMode.Off) {
            foundation
        } else {
            registerShrinkTrainJar(
                "shrinkFoundation",
                foundation.flatMap { it.archiveFile }.map { it.asFile },
                version.map { bundleJarName("foundation", it) },
                "foundation",
                mode,
            ) {
                keptJars.from(contracts)
                libraryJars.from(jarsOf(TrainPart.Platform))
            }.also { shrunk ->
                // `assembleFoundation` stays the task that gives the final jar.
                tasks.register("assembleFoundation") {
                    group = "build"
                    description = "Assembles and processes the foundation jar of the bundle train."
                    dependsOn(shrunk)
                }
            }.flatMap { it.output }
        }
        // The launcher is not processed: it starts before the train and is read by the selector as it is.
        val launcherJars = jarsOf(TrainPart.Launcher)
        val launcher = registerBaseJar(LAUNCHER_ID, version, launcherJars, null, FINAL_JAR_DIRECTORY, "assembleLauncher")
        launcher.configure {
            // An empty jar is valid and reproducible, and the manifest would sign it as a launcher that cannot start.
            doFirst { check(launcherJars.get().isNotEmpty()) { "$LAUNCHER_PROJECT is not on the runtime classpath" } }
        }
        rootProject.tasks.matching { it.name == MANIFEST_TASK }.configureEach {
            this as BundleManifest
            baseArtifacts.put("foundation", version)
            baseArtifacts.put("contracts", version)
            baseArtifacts.put(LAUNCHER_ID, version)
            jars.from(foundationJar, contracts, launcher)
        }
    }

    private fun Project.registerBaseJar(
        id: String,
        version: Provider<String>,
        projectJars: Provider<List<File>>,
        ownClasses: Any?,
        directory: String,
        taskName: String,
    ): TaskProvider<Jar> = tasks.register<Jar>(taskName) {
        group = "build"
        description = "Assembles the reproducible jar of the base artifact $id of the bundle train."
        archiveFileName.set(version.map { bundleJarName(id, it) })
        destinationDirectory.set(layout.buildDirectory.dir(directory))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.FAIL
        filePermissions { unix(JAR_FILE_MODE) }
        dirPermissions { unix(JAR_DIR_MODE) }
        exclude("META-INF/MANIFEST.MF", "module-info.class")
        // The jars are picked from the resolved runtime classpath at execution time: build every project it holds.
        dependsOn(configurations.named("runtimeClasspath"))
        if (ownClasses != null) from(ownClasses)
        from({ projectJars.get().map { zipTree(it) } })
    }

    private companion object {
        const val VERSION_PROPERTY = "heartbeat.bundleVersion"
        const val MANIFEST_TASK = "bundleManifest"
        const val JAR_FILE_MODE = "rw-r--r--"
        const val JAR_DIR_MODE = "rwxr-xr-x"
    }
}

private fun ResolvedArtifactResult.projectPath(): String? = (id.componentIdentifier as? ProjectComponentIdentifier)?.projectPath

/** The `…:impl` modules that the `:bundles:*` assemblers depend on directly: their classes are in bundle jars. */
internal fun Project.bundleImplPaths(): Set<String> = subprojects
    .filter { it.parent?.path == ":bundles" }
    .flatMap { bundle ->
        bundle.configurations.flatMap { it.dependencies.withType(ProjectDependency::class.java).map { dep -> dep.path } }
    }
    .filter { it.endsWith(":impl") }
    .toSet()
