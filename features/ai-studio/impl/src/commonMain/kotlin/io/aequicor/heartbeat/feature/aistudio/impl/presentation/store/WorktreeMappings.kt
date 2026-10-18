package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.BuildPhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.BuildUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeActionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreePhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeUi
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeAction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.collections.immutable.toImmutableList

internal fun WorktreeTask.toUi(): WorktreeUi = WorktreeUi(
    WorktreePhaseUi.valueOf(phase.name),
    branch,
    sourceBranch,
    run?.summary,
    verifiedPullRequestUrl?.takeIf { it.startsWith("https://") },
    failure,
    builds.values.map {
        BuildUi(
            it.id,
            it.command,
            BuildPhaseUi.valueOf(it.phase.name),
            it.output,
            it.failure,
            it.queuePosition,
        )
    }
        .toImmutableList(),
    isSandbox = isSandbox,
    taskId = chatId,
    sandboxPhase = virtualSandbox?.phase?.name,
    isSandboxResolving = virtualSandbox?.isResolving == true,
    sandboxConflicts = virtualSandbox?.conflicts.orEmpty().toImmutableList(),
    resultCommit = virtualSandbox?.resultCommit,
)

internal fun WorktreeActionUi.toDomain(): WorktreeAction = WorktreeAction.valueOf(name)
