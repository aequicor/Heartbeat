package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeModeEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
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
 * the build budget, project list or task phases change. Busy surplus slots are retried until their workers release
 * them; profile closure cancels the retry. The journal is read only after the machine finishes restoration.
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
    private val machine: Lazy<Machine<WorktreeState, WorktreeIntent, WorktreeOutput>>,
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
                    machine.value.state.filterIsInstance<WorktreeState.Ready>().map { state ->
                        state.tasks.values.filter { it.isSandbox }.associate { it.chatId to it.phase }
                    }.distinctUntilChanged(),
                ) { worktree, sandbox, _, _, _ -> worktree && sandbox }
                    .conflate()
                    .collectLatest { enabled ->
                        while (sweep(enabled)) delay(RETRY_MILLIS)
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(IllegalStateException("SlotSweeperStopped (${error::class.simpleName.orEmpty()})")) {
                    "Build slot sweeping stopped for this profile session"
                }
            }
        }
    }

    /** True only while a surplus slot is busy; no periodic work is scheduled once all eligible slots are removed. */
    private suspend fun sweep(isSandboxEnabled: Boolean): Boolean {
        val git = git.value
        if (!git.isAvailable) return false
        return try {
            val slots = capacity.budget().slotsPerRepository
            var hasBusySlots = false
            journal.value.sandboxRecords().groupBy { it.commonDirectory }.values.forEach { records ->
                val keep = slotsToKeep(
                    isSandboxEnabled,
                    hasWorkingSandbox = records.any { it.task.phase !in idlePhases },
                    isProjectKnown = records.any { workspaces.value.resolve(it.task.project) != null },
                    slots,
                )
                if (git.sweepSlots(records.first(), keep)) hasBusySlots = true
            }
            hasBusySlots
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("SlotSweepFailed (${error::class.simpleName.orEmpty()})")) {
                "Surplus build slots remain until the next change"
            }
            false
        }
    }

    private companion object {
        const val RETRY_MILLIS = 2_000L
        val idlePhases = setOf(WorktreePhase.Retained, WorktreePhase.Failed)
    }
}
