package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Root-level files and directories whose change can alter every module: all bundles are rebuilt. */
private val GLOBAL_PATHS = listOf("build-logic/", "gradle/")
private val GLOBAL_FILES = setOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties")

private fun normalized(changedPaths: Collection<String>): List<String> =
    changedPaths.map { it.trim().replace('\\', '/').removePrefix("./") }.filter { it.isNotEmpty() }

private fun isBuildFile(path: String): Boolean = path in GLOBAL_FILES || GLOBAL_PATHS.any { path.startsWith(it) }

/**
 * Whether the launcher jar of the train may change with [changedPaths]: the directory of the launcher project, or the
 * build itself (build-logic, version catalog, root scripts), which can change any jar. The launcher is a part of the
 * train (its jar, signed), but no bundle depends on it, so a change here publishes a train with the bundles unchanged.
 * The launcher selector is not the launcher: it ships in the installer, not in the train. The directory comes from
 * [projectDirs] (`project path -> directory`), so moving the project cannot silently stop publication; a build without
 * the launcher project counts as affected.
 */
internal fun launcherAffected(changedPaths: Collection<String>, projectDirs: Map<String, String>): Boolean {
    val directory = projectDirs[LAUNCHER_PROJECT] ?: return true
    return normalized(changedPaths).any { isBuildFile(it) || it == directory || it.startsWith("$directory/") }
}

/**
 * Bundles that must be rebuilt for [changedPaths] (repository-relative, `/`-separated).
 *
 * A changed file belongs to the project with the longest matching directory; every project that depends on it,
 * directly or transitively, is affected, and so is a bundle among them. Files of the build itself (build-logic,
 * version catalog, root scripts) affect all [bundles]. Files outside any project (docs, scripts, CI) affect none.
 *
 * @param projectDirs `project path -> repository-relative directory`, without the root project.
 * @param dependencies `project path -> project paths it depends on directly`.
 * @param bundles paths of the `:bundles:*` projects.
 */
internal fun affectedBundles(
    changedPaths: Collection<String>,
    projectDirs: Map<String, String>,
    dependencies: Map<String, Collection<String>>,
    bundles: Set<String>,
): List<String> {
    val paths = normalized(changedPaths)
    if (paths.any(::isBuildFile)) {
        return bundles.sorted()
    }
    val dirs = projectDirs.entries.sortedByDescending { it.value.length }
    val dependants = HashMap<String, MutableSet<String>>()
    for ((project, deps) in dependencies) {
        for (dep in deps) dependants.getOrPut(dep) { mutableSetOf() }.add(project)
    }
    val affected = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    for (path in paths) {
        val owner = dirs.firstOrNull { (_, dir) -> path == dir || path.startsWith("$dir/") }?.key
        if (owner != null && affected.add(owner)) queue.add(owner)
    }
    while (queue.isNotEmpty()) {
        for (dependant in dependants[queue.removeFirst()].orEmpty()) {
            if (affected.add(dependant)) queue.add(dependant)
        }
    }
    return affected.filter { it in bundles }.sorted()
}

/**
 * Root task `affectedBundles`: prints and writes the bundles to rebuild for the changed paths given as
 * `-Pheartbeat.changedPaths=a,b` (comma-separated) or `-Pheartbeat.changedFile=<file>` (one path per line), and whether
 * the launcher of the train may have changed (`launcher.txt`, `true` or `false`: a train is published for it too).
 */
abstract class AffectedBundlesTask : DefaultTask() {
    /** `project path -> repository-relative directory`, computed from the Gradle project graph. */
    @get:Input
    abstract val projectDirs: MapProperty<String, String>

    /** `project path -> direct project dependencies`, computed from the Gradle project graph. */
    @get:Input
    abstract val projectDependencies: MapProperty<String, List<String>>

    @get:Input
    abstract val bundles: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val changedPaths: ListProperty<String>

    @get:OutputFile
    abstract val resultFile: RegularFileProperty

    /** `true` or `false`: the launcher jar of the train may change, so a train has to be published for it too. */
    @get:OutputFile
    abstract val launcherFile: RegularFileProperty

    @TaskAction
    fun compute() {
        val paths = changedPaths.getOrElse(emptyList())
        val result = affectedBundles(
            changedPaths = paths,
            projectDirs = projectDirs.get(),
            dependencies = projectDependencies.get(),
            bundles = bundles.get().toSet(),
        )
        val text = result.joinToString("\n")
        resultFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(if (text.isEmpty()) "" else text + "\n")
        }
        val launcher = launcherAffected(paths, projectDirs.get())
        launcherFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("$launcher\n")
        }
        logger.lifecycle("Affected bundles: " + result.ifEmpty { listOf("none") }.joinToString())
        logger.lifecycle("Affected launcher: $launcher")
    }
}
