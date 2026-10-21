package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding.ProjectRoot
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Only adapter-attested execution contracts are analyzed; a native name/category cannot create such a contract. */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class DesktopNativeActionAnalysis(
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
) : NativeActionAnalysis {
    override suspend fun analyze(context: AgentToolContext, call: NativeToolCall): ActionAnalysis {
        val shell = call.shell ?: return unknown()
        val command = call.command ?: return unknown()
        val cwd = call.workingDirectory ?: return unknown()
        val workspace = context.workspace ?: return unknown()
        if (!workspaces.isAvailable || workspaces.virtual(workspace) != null) return unknown()
        val directory = workspaces.resolve(workspace) ?: return unknown()
        return withContext(dispatchers.io) {
            ShellActionAnalyzer(dispatchers.io).analyze(ProjectRoot(Path.of(directory)), Path.of(cwd), command, shell)
        }
    }
}

private fun unknown(): ActionAnalysis = ActionAnalysis.Unknown("Native execution has no host-attested action contract")
