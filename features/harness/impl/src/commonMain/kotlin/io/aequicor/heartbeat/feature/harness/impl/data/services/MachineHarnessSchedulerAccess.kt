package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.activeHarnesses
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessProjectSnapshots
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccessSnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.sourceProjectOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Uses persisted scheduler routing and attachments even before any facade callback after restart. Reading the
 * registry never starts the library or an engine. A helper must be durably attached before its first prompt;
 * neither owner text nor an action-id prefix can prove helper affiliation. Until the library, the enabled
 * generation and the needed worktree mapping are restored, a decision is [HarnessAccess.Unknown], never a refusal.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineHarnessSchedulerAccess(
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val projects: HarnessProjectSnapshots,
    private val gate: HarnessEventGate,
) : HarnessSchedulerAccess {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun permits(harness: HarnessId, target: HarnessTarget?): Flow<HarnessAccess> =
        toggles.observe(HarnessEnabled).flatMapLatest { enabled ->
            if (!enabled) {
                flowOf(HarnessAccess.Denied)
            } else {
                combine(
                    machines.observe(HarnessMachineKey).flatMapLatest { it?.state ?: flowOf(null) },
                    machines.observe(WorktreeMachineKey).flatMapLatest { it?.state ?: flowOf(null) },
                    projects.projects,
                    gate.epochs,
                ) { library, worktrees, catalog, epoch ->
                    val isAllowed = HarnessSchedulerSnapshot(library, worktrees, catalog, epoch, gate.hasOpened)
                        .allows(harness, target)
                    // A permit is bound to its enabled generation; other decisions do not depend on it.
                    isAllowed to epoch.takeIf { isAllowed == true }
                }.distinctUntilChanged().map { (isAllowed, epoch) ->
                    when (isAllowed) {
                        true -> HarnessAccess.Permitted(HarnessDeliveryPermit { isCurrent(harness, target, epoch) })
                        false -> HarnessAccess.Denied
                        null -> HarnessAccess.Unknown
                    }
                }
            }
        }

    override fun snapshot(): HarnessAccessSnapshot = current()

    /** Only this decision and its enabled generation matter; unrelated library edits keep the permit current. */
    private suspend fun isCurrent(harness: HarnessId, target: HarnessTarget?, epoch: Long?): Boolean {
        if (!toggles.get(HarnessEnabled)) return false
        val current = current()
        return current.epoch == epoch && current.allows(harness, target) == true
    }

    private fun current(): HarnessSchedulerSnapshot = HarnessSchedulerSnapshot(
        machines.find(HarnessMachineKey)?.state?.value,
        machines.find(WorktreeMachineKey)?.state?.value,
        projects.projects.value,
        gate.currentEpoch,
        gate.hasOpened,
    )
}

/** Null admission means project provenance is still unknown, not a license to treat a checkout as its source. */
internal data class HarnessSchedulerSnapshot(
    val library: HarnessState?,
    val worktrees: WorktreeState?,
    val projects: Set<WorkspaceRef>?,
    val epoch: Long?,
    val hasOpened: Boolean = true,
) : HarnessAccessSnapshot {
    override fun allows(harness: HarnessId, target: HarnessTarget?): Boolean? {
        if (library?.isSuspended == true) return false
        if (epoch == null) return if (hasOpened) false else null
        val ready = when (library) {
            null, is HarnessState.Idle, is HarnessState.Loading -> return null
            is HarnessState.Failed -> return false
            is HarnessState.Ready -> library
        }
        if (!ready.isRuntimeAvailable) return false
        val enabled = ready.harnesses.map { it.harness }.filter {
            it.isEnabled && ready.pending[it.id] !is HarnessMutation.Remove
        }
        if (enabled.none { it.id == harness }) return false
        return if (target == null) true else allowsTarget(ready, enabled, harness, target)
    }

    private fun allowsTarget(
        ready: HarnessState.Ready,
        enabled: List<Harness>,
        harness: HarnessId,
        target: HarnessTarget,
    ): Boolean? {
        val workspace = target.workspace
        val source = if (workspace == null) {
            null
        } else {
            val restored = worktrees as? WorktreeState.Ready ?: return null
            restored.sourceProjectOf(workspace) ?: workspace.takeIf { projects?.contains(it) == true } ?: return null
        }
        return activeHarnesses(enabled, ready.attachments, target.session, source).any { it.id == harness }
    }

    override fun toString(): String = "HarnessSchedulerSnapshot(***)"
}
