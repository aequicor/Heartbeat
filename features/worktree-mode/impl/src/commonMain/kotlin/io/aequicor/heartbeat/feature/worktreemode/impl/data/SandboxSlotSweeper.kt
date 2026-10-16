package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeModeEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Slots a repository keeps on disk: none once its project is gone from the profile, none when sandboxes are off and
 * no sandbox task of the repository still works, otherwise the slots the current budget may use.
 */
internal fun slotsToKeep(isSandboxEnabled: Boolean, hasWorkingSandbox: Boolean, isProjectKnown: Boolean, slots: Int) =
    when {
        !isProjectKnown -> 0
        !isSandboxEnabled && !hasWorkingSandbox -> 0
        else -> slots
    }

/**
 * Removes surplus build slots of the profile's sandbox repositories when the profile opens and whenever sandboxes,
 * the build budget or the project list change, so a lowered limit or a disabled mode returns disk space.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class SandboxSlotSweeper(
    // Profile storage is opened only by the sweep itself, never while the profile graph starts.
    private val journal: Lazy<WorktreeJournal>,
    private val git: Lazy<WorktreeGit>,
    private val capacity: BuildCapacity,
    private val toggles: FeatureToggles,
    private val workspaces: Lazy<LocalWorkspaces>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    private val log = Log.tag("SandboxSlotSweeper")

    override fun start() {
        profile.coroutineScope.launch {
            try {
                combine(
                    toggles.observe(WorktreeModeEnabled),
                    toggles.observe(WorktreeSandboxEnabled),
                    capacity.changes().onStart { emit(Unit) },
                    workspaces.value.observe(),
                ) { worktree, sandbox, _, _ -> worktree && sandbox }
                    .conflate()
                    .collect { sweep(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(IllegalStateException("SlotSweeperStopped (${error::class.simpleName.orEmpty()})")) {
                    "Build slot sweeping stopped for this profile session"
                }
            }
        }
    }

    private suspend fun sweep(isSandboxEnabled: Boolean) {
        val git = git.value
        if (!git.isAvailable) return
        try {
            val slots = capacity.budget().slotsPerRepository
            journal.value.sandboxRecords().groupBy { it.commonDirectory }.values.forEach { records ->
                val keep = slotsToKeep(
                    isSandboxEnabled,
                    hasWorkingSandbox = records.any { it.task.phase !in idlePhases },
                    isProjectKnown = records.any { workspaces.value.resolve(it.task.project) != null },
                    slots,
                )
                git.sweepSlots(records.first(), keep)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("SlotSweepFailed (${error::class.simpleName.orEmpty()})")) {
                "Surplus build slots remain until the next change"
            }
        }
    }

    private companion object {
        val idlePhases = setOf(WorktreePhase.Retained, WorktreePhase.Failed)
    }
}
