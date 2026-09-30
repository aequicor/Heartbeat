package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeEffect
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import kotlinx.coroutines.CancellationException

/** Durable results are emitted after IO; sibling task effects remain active in the profile Ready state. */
@Inject
internal class WorktreeEffects(private val journal: WorktreeJournal) : EffectHandler<WorktreeEffect, WorktreeIntent> {
    private val log = Log.tag("WorktreeEffects")

    override suspend fun handle(effect: WorktreeEffect, machine: EffectScope<WorktreeIntent>) {
        when (effect) {
            WorktreeEffect.Load -> machine.send(WorktreeIntent.Internal.Loaded(journal.load()))

            WorktreeEffect.ObserveWorkers -> journal.observeWorkers {
                machine.send(
                    WorktreeIntent.Internal.Updated(it),
                )
            }

            is WorktreeEffect.Apply -> try {
                journal.apply(effect.command) { machine.send(WorktreeIntent.Internal.Updated(it)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(IllegalStateException("WorktreeOperationFailed (${error::class.simpleName.orEmpty()})")) {
                    "Worktree operation failed type=${effect.command::class.simpleName.orEmpty()}"
                }
                journal.failed(
                    effect.command,
                    error.message?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: "OperationFailed",
                )
                    ?.let { machine.send(WorktreeIntent.Internal.Updated(it)) }
            }
        }
    }
}
