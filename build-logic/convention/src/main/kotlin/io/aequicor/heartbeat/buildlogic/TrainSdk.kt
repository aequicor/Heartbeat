package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * The SDK of the bundle train: the foundation modules that bundles compile against. Their public API (and every
 * `:features:*:api` contract) is the boundary between independently processed jars of the train: it is never renamed
 * nor removed, while everything else in the foundation and inside bundles may be shrunk and obfuscated.
 *
 * Declared in the desktop application module: `heartbeatTrain { sdk(projects.core.logging, …) }`.
 */
abstract class TrainSdkExtension {
    /** Gradle paths of the SDK modules of the foundation. */
    abstract val sdk: SetProperty<String>

    /** Adds [projects] to the SDK. */
    fun sdk(vararg projects: ProjectDependency) {
        sdk.addAll(projects.map { it.path })
    }
}

/**
 * Whether a configuration puts its project dependencies on the desktop compile or runtime class path of the main code:
 * JVM modules' `api`/`implementation`/`compileOnly`/`runtimeOnly` and the common and JVM main source sets of KMP ones.
 * Tests, Android and Apple source sets are not part of the desktop train.
 */
internal fun isDesktopMainConfiguration(name: String): Boolean =
    (name in JVM_MAIN_CONFIGURATIONS || DESKTOP_SOURCE_SETS.any { name.startsWith(it) }) &&
        "Test" !in name && !name.endsWith("Metadata")

/** Whether a desktop main configuration also exposes its dependencies to the consumers' compile class path. */
internal fun isApiConfiguration(name: String): Boolean = isDesktopMainConfiguration(name) && name.endsWith("Api", ignoreCase = true)

private val JVM_MAIN_CONFIGURATIONS = setOf("api", "implementation", "compileOnly", "runtimeOnly")
private val DESKTOP_SOURCE_SETS = listOf("commonMain", "jvmMain")

/**
 * Foundation modules that bundles reach outside the SDK.
 *
 * [bundleDependencies] is `bundle path -> project dependencies` of the bundle assembler and of its own `…:impl`
 * modules; [apiDependencies] is `project path -> api project dependencies` of every project. A bundle compiles against
 * its direct dependencies and, transitively, the api dependencies of those that are contracts or SDK modules: each
 * foundation module among them must be in [sdk]. [partOf] tells the train part of a project path.
 *
 * Wrong SDK entries are reported too: a path that is not a foundation module of the build.
 */
internal fun trainSdkViolations(
    bundleDependencies: Map<String, Collection<String>>,
    apiDependencies: Map<String, Collection<String>>,
    sdk: Set<String>,
    partOf: (String) -> TrainPart,
): List<String> {
    val misdeclared = sdk.filter { it !in apiDependencies || partOf(it) != TrainPart.Foundation }
        .map { "$it is in the SDK but is not a foundation module" }
    val leaks = bundleDependencies.flatMap { (bundle, direct) ->
        val reached = mutableMapOf<String, String>()
        val queue = ArrayDeque<Pair<String, String>>()
        for (dependency in direct) queue.add(dependency to bundle)
        while (queue.isNotEmpty()) {
            val (path, via) = queue.removeFirst()
            if (path in reached) continue
            reached[path] = via
            // A bundle sees the api of contracts and SDK modules; what a non-SDK module exposes is reported already.
            if (partOf(path) == TrainPart.Contracts || path in sdk) {
                apiDependencies[path].orEmpty().forEach { queue.add(it to path) }
            }
        }
        reached.filter { (path, _) -> partOf(path) == TrainPart.Foundation && path !in sdk }
            .map { (path, via) -> "$bundle reaches $path (via $via), which is not in the SDK" }
    }
    return (misdeclared + leaks).distinct().sorted()
}

/**
 * Root task `verifyTrainSdk`: fails when a bundle compiles against a foundation module outside the SDK
 * ([TrainSdkExtension]). Processed jars keep only the SDK by name, so such a reference would break at run time.
 */
abstract class VerifyTrainSdk : DefaultTask() {
    @get:Input
    abstract val sdk: SetProperty<String>

    /** `bundle path -> project dependencies` of the assembler and its `…:impl` modules. */
    @get:Input
    abstract val bundleDependencies: MapProperty<String, List<String>>

    /** `project path -> api project dependencies`. */
    @get:Input
    abstract val apiDependencies: MapProperty<String, List<String>>

    /** The `…:impl` modules that bundle jars carry. */
    @get:Input
    abstract val bundleImpls: ListProperty<String>

    @TaskAction
    fun verify() {
        val impls = bundleImpls.get().toSet()
        val violations = trainSdkViolations(
            bundleDependencies.get(),
            apiDependencies.get(),
            sdk.get(),
        ) { trainPartOf(it, impls) }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Bundles may compile only against contracts and the SDK modules of the foundation " +
                    "(heartbeatTrain { sdk(...) } in the desktop module):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}

/** Registers the root `verifyTrainSdk` task over every bundle of the build with the SDK of [extension]. */
internal fun Project.registerVerifyTrainSdk(extension: TrainSdkExtension) {
    val root = rootProject
    root.tasks.register(VERIFY_TRAIN_SDK_TASK, VerifyTrainSdk::class.java) {
        group = "verification"
        description = "Fails when a bundle compiles against a foundation module outside the train SDK."
        sdk.set(extension.sdk)
        val bundles = root.provider { root.subprojects.filter { it.parent?.path == ":bundles" } }
        bundleImpls.set(root.provider { root.bundleImplPaths().sorted() })
        bundleDependencies.set(
            bundles.map { all ->
                all.associate { bundle ->
                    val own = bundle.desktopDependencyPaths(::isDesktopMainConfiguration)
                    val implDependencies = own.filter { it.endsWith(":impl") }
                        .mapNotNull { root.findProject(it) }
                        .flatMap { it.desktopDependencyPaths(::isDesktopMainConfiguration) }
                    // Own impls are the bundle itself, not something it reaches.
                    bundle.path to (own + implDependencies).filterNot { it in own && it.endsWith(":impl") }.distinct().sorted()
                }
            },
        )
        apiDependencies.set(
            root.provider { root.subprojects.associate { it.path to it.desktopDependencyPaths(::isApiConfiguration) } },
        )
    }
}

private fun Project.desktopDependencyPaths(filter: (String) -> Boolean): List<String> = configurations
    .filter { filter(it.name) }
    .flatMap { configuration -> configuration.dependencies.withType(ProjectDependency::class.java).map { it.path } }
    .filter { it != path }
    .distinct()
    .sorted()

internal const val VERIFY_TRAIN_SDK_TASK = "verifyTrainSdk"
