package io.aequicor.heartbeat.feature.agentlearning.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEffect
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningIntent
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.agentlearning.api.LearningApproval
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The stored registry of one profile. */
internal data class StoredLearning(
    val instructions: List<LearnedInstruction> = emptyList(),
    val approval: LearningApproval = LearningApproval.Ask,
)

/** Profile storage of learned instructions and the approval level. */
internal interface LearningStorage {
    /** The stored registry; defaults when nothing was saved. Unreadable data fails instead of reading as empty. */
    suspend fun load(): StoredLearning

    /** Replaces the stored instructions. */
    suspend fun saveInstructions(instructions: List<LearnedInstruction>)

    /** Replaces the stored approval level. */
    suspend fun saveApproval(approval: LearningApproval)
}

/**
 * Executes registry effects; failures are mapped by the spec's `onEffectFailure`. Saves run one at a time and a
 * save older than the last written revision is dropped, so concurrent effects never leave a stale registry behind.
 * Only counts and ids are logged: instruction texts may describe private projects.
 */
internal class AgentLearningEffects(private val storage: LearningStorage) :
    EffectHandler<AgentLearningEffect, AgentLearningIntent> {

    private val log = Log.tag("AgentLearningEffects")
    private val saving = Mutex()
    private var savedRevision = -1L
    private var savedApprovalRevision = -1L

    override suspend fun handle(effect: AgentLearningEffect, machine: EffectScope<AgentLearningIntent>) {
        when (effect) {
            AgentLearningEffect.Load -> {
                val stored = storage.load()
                log.i { "learned instructions loaded: ${stored.instructions.size}, approval ${stored.approval}" }
                machine.send(AgentLearningIntent.Internal.Loaded(stored.instructions, stored.approval))
            }

            is AgentLearningEffect.Persist -> {
                saving.withLock {
                    if (effect.revision <= savedRevision) {
                        // A newer revision already holds every change of this one.
                        log.d { "stale registry save skipped: ${effect.revision} <= $savedRevision" }
                    } else {
                        storage.saveInstructions(effect.instructions)
                        savedRevision = effect.revision
                        log.i { "learned instructions saved: ${effect.instructions.size}, revision ${effect.revision}" }
                    }
                }
                effect.receipt?.let { machine.send(AgentLearningIntent.Internal.Saved(it)) }
            }

            is AgentLearningEffect.PersistApproval -> saving.withLock {
                if (effect.revision <= savedApprovalRevision) {
                    log.d { "stale approval save skipped: ${effect.revision} <= $savedApprovalRevision" }
                } else {
                    storage.saveApproval(effect.approval)
                    savedApprovalRevision = effect.revision
                    log.i { "learning approval saved: ${effect.approval}" }
                }
            }
        }
    }
}
