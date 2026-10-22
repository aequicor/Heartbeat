package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceState
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.coroutines.withContext

/**
 * Uses the adapter's bounded resource inventory and saved launch selection: masks, kind-off flags and project
 * precedence match the running Pi version. Discovery never starts a runtime. Untrusted projects contribute no
 * native rows, even for resources Pi might otherwise read; a chat without a project can use profile resources.
 */
internal class PiComposerAssists(
    private val configurator: PiConfigurator,
    private val launches: EngineLaunchConfig,
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
) : ListsComposerAssists {
    override suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist> {
        val before = launches.context(PiEngineId)
        val trust = workspace?.let { before.trust[it] } ?: ProjectTrust.Undecided
        if (workspace != null && trust != ProjectTrust.Trusted) return emptyList()
        val directory = workspace?.let { workspaces.resolve(it) ?: return emptyList() }
        val result = withContext(dispatchers.io) {
            val snapshot = configurator.composerResources(before.config, directory, trust)
            val isSkillCommandsEnabled = snapshot.isSkillCommandsEnabled
            snapshot.commands.asSequence()
                .filter { it.state == ResourceState.Enabled && NAME.matches(it.name) }
                .distinctBy { it.kind to it.name }
                .flatMap { resource -> resourceAssists(resource, isSkillCommandsEnabled) }.toList()
        }
        return result.takeIf { launches.context(PiEngineId) == before }.orEmpty()
    }

    private fun resourceAssists(resource: EngineResource, isSkillCommandsEnabled: Boolean): List<EngineAssist> {
        val id = "${resource.origin}:${resource.path}"
        val skill = EngineAssist(
            id,
            resource.name,
            resource.description.orEmpty(),
            "${resource.name} ",
            EngineAssist.Kind.Skill,
        )
        return when (resource.kind) {
            ResourceKind.Skill -> listOf(skill) + if (isSkillCommandsEnabled) {
                listOf(
                    skill.copy(
                        id = "command:$id",
                        insert = "/skill:${resource.name} ",
                        kind = EngineAssist.Kind.Command,
                    ),
                )
            } else {
                emptyList()
            }

            ResourceKind.Prompt -> listOf(
                skill.copy(insert = "/${resource.name} ", kind = EngineAssist.Kind.Command),
            )

            ResourceKind.Extension -> emptyList()
        }
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}")
    }
}
