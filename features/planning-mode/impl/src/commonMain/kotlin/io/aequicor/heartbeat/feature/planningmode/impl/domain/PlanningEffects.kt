package io.aequicor.heartbeat.feature.planningmode.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import io.aequicor.heartbeat.feature.planningmode.api.PlanningEffect
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningOutput
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.externallyEdited
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** The planning machine of the profile. */
internal typealias PlanningMachine = Machine<PlanningState, PlanningIntent, PlanningOutput>

/**
 * Executes the effects of the planning machine; failures are mapped by the spec's `onEffectFailure`. Saves run one
 * at a time and a save not newer than the last handled one is dropped, so a late save never brings an older plan
 * back. Every save writes each plan whose revision is newer than the one written last and repeats deletions that
 * failed, so a failed save is completed by the next one. Files are written before the index and carry their
 * revision: after an interruption the plan loads with the newer text and its own revision.
 */
internal class PlanningEffects(
    private val index: PlanIndexStore,
    private val files: PlanFiles,
    private val clock: Clock,
) : EffectHandler<PlanningEffect, PlanningIntent> {
    private val log = Log.tag("PlanningEffects")
    private val saving = Mutex()
    private var handledRevision = -1L
    private val fileRevisions = mutableMapOf<PlanChat, Long>()

    /** Chats whose plan is gone but whose files may still exist; kept until a delete succeeds. */
    private val deletions = mutableSetOf<PlanChat>()

    override suspend fun handle(effect: PlanningEffect, machine: EffectScope<PlanningIntent>) {
        when (effect) {
            PlanningEffect.Load -> machine.send(load())
            is PlanningEffect.Persist -> saving.withLock { persist(effect) }
        }
    }

    private suspend fun load(): PlanningIntent.Internal.Loaded = saving.withLock {
        val stored = index.load() ?: PlanIndex()
        val repaired = mutableSetOf<PlanChat>()
        val plans = stored.plans.associate { record ->
            val file = files.read(record.chat)
            val plan = when {
                file == null || file.isOversized -> record.copy(text = "")
                file.isExternal -> externallyEdited(record, file.text, clock.now(), file.revision ?: record.revision)
                (file.revision ?: 0) > record.revision -> recovered(record, file.text, file.revision ?: 0)
                else -> record.copy(text = file.text)
            }
            if (plan.revision != record.revision) repaired += record.chat
            // An external edit is written again with its new revision; other files are as the studio wrote them.
            if (file != null && file.isExternal && !file.isOversized) {
                fileRevisions.remove(record.chat)
            } else {
                fileRevisions[record.chat] = plan.revision
            }
            record.chat to plan
        }
        val bindings = stored.bindings.filter { it.chat in plans }.associate { it.session to it.chat }
        log.i { "plans loaded: plans=${plans.size} bindings=${bindings.size} repaired=${repaired.size}" }
        PlanningIntent.Internal.Loaded(plans, bindings, repaired)
    }

    /**
     * [record] with the [text] of a newer [revision] the studio wrote before its index save was interrupted. Who wrote
     * it and whether it was reviewed are unknown, so it follows a write: a plan being planned returns to a draft.
     */
    private fun recovered(record: Plan, text: String, revision: Long): Plan = record.copy(
        text = text,
        revision = revision,
        status = if (record.mode == PlanMode.Planning) PlanStatus.Draft else record.status,
        updatedAt = clock.now(),
        updatedBy = PlanAuthor.External,
    )

    private suspend fun persist(effect: PlanningEffect.Persist) {
        if (effect.revision <= handledRevision) {
            log.v { "stale plan save skipped: ${effect.revision} <= $handledRevision" }
            return
        }
        handledRevision = effect.revision
        val gone = (fileRevisions.keys + effect.changed) - effect.plans.keys
        deletions.addAll(gone)
        gone.forEach(fileRevisions::remove)
        // A new plan of a chat replaces its files instead of deleting them.
        deletions.removeAll(effect.plans.keys)
        effect.plans.values.forEach { plan ->
            if (plan.revision > (fileRevisions[plan.chat] ?: -1)) {
                files.write(plan.chat, plan.text, plan.revision)
                fileRevisions[plan.chat] = plan.revision
                log.v { "plan written: chat=${plan.chat} revision=${plan.revision} length=${plan.text.length}" }
            }
        }
        deletions.toList().forEach { chat ->
            files.delete(chat)
            deletions.remove(chat)
            log.i { "plan deleted: chat=$chat" }
        }
        val records = effect.plans.values.map { it.copy(text = "") }
        val bindings = effect.bindings.map { (session, chat) -> PlanBinding(session, chat) }
        index.save(PlanIndex(records, bindings))
        log.v { "plan index saved: plans=${records.size} revision=${effect.revision}" }
    }
}
