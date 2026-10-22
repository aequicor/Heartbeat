package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AssistDirectory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectAssistReader

/** Native Codex skill mention syntax; the app-server receives the inserted text unchanged. */
internal class CodexComposerAssists(private val reader: ProjectAssistReader) : ListsComposerAssists {
    override suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist> = reader.read(
        CodexEngine.Id,
        workspace,
        listOf(AssistDirectory(".agents/skills", EngineAssist.Kind.Skill, isSkill = true, prefix = "$")),
    )
}
