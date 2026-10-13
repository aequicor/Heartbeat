package io.aequicor.heartbeat.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.RootPackageJsonTask
import java.io.File

/**
 * Kotlin 2.4.20 writes local Yarn workspace globs using OS path separators. Yarn ignores the
 * backslash-separated paths on Windows, so dependencies of the panel can be missing at webpack time.
 * Normalize the generated manifest before npm installation; apply once at the root for all Wasm modules.
 */
internal class WasmWorkspacesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.tasks.withType<RootPackageJsonTask>().configureEach {
            if (name == "wasmRootPackageJson") doLast(NormalizeYarnWorkspaces())
        }
    }
}

private class NormalizeYarnWorkspaces : Action<Task> {
    override fun execute(task: Task) {
        normalizeYarnWorkspacePaths((task as RootPackageJsonTask).rootPackageJsonFile.get().asFile)
    }
}

/** Normalize workspace globs only, preserving dependency versions and other manifest fields. */
internal fun normalizeYarnWorkspacePaths(file: File) {
    val manifest = JsonSlurper().parse(file) as Map<*, *>
    val workspaces = manifest["workspaces"] as? List<*> ?: return
    val normalized = workspaces.map { (it as String).replace('\\', '/') }
    if (normalized == workspaces) return
    file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest + ("workspaces" to normalized))) + "\n")
}
