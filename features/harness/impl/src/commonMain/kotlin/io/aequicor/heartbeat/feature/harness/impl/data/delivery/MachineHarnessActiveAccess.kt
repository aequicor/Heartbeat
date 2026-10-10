package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessProjectSnapshots
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveSnapshot
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** Registry reads do not start engines or the library and therefore cannot recurse into tool contributions. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineHarnessActiveAccess(
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val projects: HarnessProjectSnapshots,
) : HarnessActiveAccess {
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun active(workspace: WorkspaceRef?, session: SessionRef?): List<Harness> {
        if (!toggles.get(HarnessEnabled)) return emptyList()
        return checkNotNull(
            withTimeoutOrNull(1_000.milliseconds) {
                combine(
                    toggles.observe(HarnessEnabled),
                    machines.observe(HarnessMachineKey).flatMapLatest { it?.state ?: flowOf(null) },
                    machines.observe(WorktreeMachineKey).flatMapLatest { it?.state ?: flowOf(null) },
                    projects.projects,
                ) { enabled, library, worktrees, catalog ->
                    HarnessActiveSnapshot(enabled, library, worktrees, catalog).select(workspace, session)
                }.filterNotNull().first()
            },
        ) { "Harness activation is unavailable" }
    }
}
