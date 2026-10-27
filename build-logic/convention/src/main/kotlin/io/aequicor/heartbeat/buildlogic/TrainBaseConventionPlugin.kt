package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
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

    /** A `:bundles:*` assembler or an `…:impl` module that a bundle jar carries. */
    Bundle,

    /** A third-party library: it ships with the installer, because its natives depend on the OS and architecture. */
    Platform,
}

/** The platform launcher starts the train, so it is on the installer's class path, not in the train. */
private const val LAUNCHER_PROJECT = ":platform-main:launcher"

private val FEATURE_API = Regex("""^:features:.+:api$""")

/**
 * Part of an artifact: [projectPath] is the Gradle path of a project artifact and null for a third-party one;
 * [bundleImpls] are the `…:impl` modules that bundle jars carry.
 */
internal fun trainPartOf(projectPath: String?, bundleImpls: Set<String>): TrainPart = when {
    projectPath == null || projectPath == LAUNCHER_PROJECT -> TrainPart.Platform
    projectPath.startsWith(":bundles:") || projectPath in bundleImpls -> TrainPart.Bundle
    FEATURE_API.matches(projectPath) -> TrainPart.Contracts
    else -> TrainPart.Foundation
}

/** Jars of the third-party libraries of the desktop app: the part of the app that stays in the installer. */
interface TrainPlatformJars {
    /** Third-party runtime jars, without any project code. */
    val jars: Provider<List<File>>
}

/**
 * `heartbeat.train` — the desktop application module (`:platform-main:desktop`) splits its runtime classpath into the
 * parts of the bundle train: `assembleFoundation` (project code outside bundles and contracts) and `assembleContracts`
 * (the `:features:*:api` modules) are reproducible jars named like bundle jars and listed as base artifacts in the root
 * `bundleManifest`. Third-party libraries are not part of the train ([TrainPlatformJars]).
 */
class TrainBaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        val version = providers.gradleProperty(VERSION_PROPERTY).orElse(provider { project.version.toString() })
        val artifacts = configurations.named("runtimeClasspath").map { configuration ->
            configuration.incoming.artifacts.resolvedArtifacts.get().toList()
        }
        val bundleImpls = provider { rootProject.bundleImplPaths() }
        fun jarsOf(part: TrainPart): Provider<List<File>> = artifacts.zip(bundleImpls) { all, impls ->
            all.filter { trainPartOf(it.projectPath(), impls) == part }.map { it.file }
        }
        extensions.add(TrainPlatformJars::class.java, "trainPlatform", object : TrainPlatformJars {
            override val jars = jarsOf(TrainPart.Platform)
        })
        val ownClasses = extensions.getByType<SourceSetContainer>().getByName("main").output
        val foundation = registerBaseJar("foundation", version, jarsOf(TrainPart.Foundation), ownClasses)
        val contracts = registerBaseJar("contracts", version, jarsOf(TrainPart.Contracts), null)
        rootProject.tasks.matching { it.name == MANIFEST_TASK }.configureEach {
            this as BundleManifest
            baseArtifacts.put("foundation", version)
            baseArtifacts.put("contracts", version)
            jars.from(foundation, contracts)
        }
    }

    private fun Project.registerBaseJar(
        id: String,
        version: Provider<String>,
        projectJars: Provider<List<File>>,
        ownClasses: Any?,
    ): TaskProvider<Jar> = tasks.register<Jar>("assemble${id.replaceFirstChar(Char::uppercase)}") {
        group = "build"
        description = "Assembles the reproducible jar of the base artifact $id of the bundle train."
        archiveFileName.set(version.map { bundleJarName(id, it) })
        destinationDirectory.set(layout.buildDirectory.dir("bundle"))
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
