package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.workspace

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogWorkspace
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogWorkspaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/** Resolves the project through the shared registry and confines file and shell tools to it. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopKoogWorkspaces(
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
) : KoogWorkspaces {
    private val log = Log.tag("KoogWorkspaces")

    override suspend fun open(ref: WorkspaceRef): KoogWorkspace? {
        if (!workspaces.isAvailable) return null
        val directory = workspaces.resolve(ref) ?: run {
            log.w { "Project of the session is unavailable; coding tools disabled" }
            return null
        }
        val root = try {
            withContext(dispatchers.io) { ProjectRoot(Path.of(directory)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.w(e) { "Project directory cannot be opened; coding tools disabled" }
            return null
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Project path is not a directory; coding tools disabled" }
            return null
        }
        val shell = KoogShellTool(root, dispatchers.io)
        log.i { "Opened coding workspace" }
        return KoogWorkspace(
            koogFileTools(root, dispatchers.io) + shell,
            codingInstructions(root.path.toString(), shell.descriptor.description),
        )
    }
}

internal fun codingInstructions(root: String, shell: String): String = listOf(
    "You are a coding agent working in the user's local project at $root (OS: ${System.getProperty("os.name")}).",
    "Inspect the project before changing it: list_dir, glob and grep find code, read_file reads it.",
    "Change files with edit_file (exact fragment replacement) or write_file (new files). run_command: $shell",
    "Paths are relative to the project root; nothing outside it is reachable.",
    "Keep changes focused on the request, follow the project's conventions, verify with builds or tests when it " +
        "is cheap, and finish with a short summary of what changed.",
    "A denied or failed tool call is not a reason to stop: explain what happened and continue or ask the user.",
).joinToString("\n")
