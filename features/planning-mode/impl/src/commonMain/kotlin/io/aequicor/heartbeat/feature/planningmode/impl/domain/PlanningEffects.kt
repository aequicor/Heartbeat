package io.aequicor.heartbeat.feature.planningmode.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
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
 * at a time: a plan file is written only when its revision is newer than the one written last, and the index only
 * when its machine revision is, so a late save never brings an older plan back. Files are written before the index:
 * after an interruption the index may name an older revision of a newer text, which loads as an external edit.
 */
internal class PlanningEffects(
    private val index: PlanIndexStore,
    private val files: PlanFiles,
    private val clock: Clock,
) : EffectHandler<PlanningEffect, PlanningIntent> {
    private val log = Log.tag("PlanningEffects")
    private val saving = Mutex()
    private var savedRevision = -1L
    private val fileRevisions = mutableMapOf<PlanChat, Long>()

    override suspend fun handle(effect: PlanningEffect, machine: EffectScope<PlanningIntent>) {
        when (effect) {
            PlanningEffect.Load -> machine.send(load())
            is PlanningEffect.Persist -> saving.withLock { persist(effect) }
        }
    }

    private suspend fun load(): PlanningIntent.Internal.Loaded = saving.withLock {
        val stored = index.load() ?: PlanIndex()
        val external = mutableSetOf<PlanChat>()
        val plans = stored.plans.associate { record ->
            fileRevisions[record.chat] = record.revision
            val file = files.read(record.chat)
            val plan = when {
                file == null -> record.copy(text = "")
                file.isExternal -> externallyEdited(record, file.text, clock.now()).also { external += record.chat }
                else -> record.copy(text = file.text)
            }
            record.chat to plan
        }
        val bindings = stored.bindings.filter { it.chat in plans }.associate { it.session to it.chat }
        log.i { "plans loaded: plans=${plans.size} bindings=${bindings.size} external=${external.size}" }
        PlanningIntent.Internal.Loaded(plans, bindings, external)
    }

    private suspend fun persist(effect: PlanningEffect.Persist) {
        effect.changed.forEach { chat -> writeFile(chat, effect.plans[chat]) }
        if (effect.revision <= savedRevision) {
            log.d { "stale plan index save skipped: ${effect.revision} <= $savedRevision" }
            return
        }
        val records = effect.plans.values.map { it.copy(text = "") }
        val bindings = effect.bindings.map { (session, chat) -> PlanBinding(session, chat) }
        index.save(PlanIndex(records, bindings))
        savedRevision = effect.revision
        log.v { "plan index saved: plans=${records.size} revision=${effect.revision}" }
    }

    private suspend fun writeFile(chat: PlanChat, plan: Plan?) {
        if (plan == null) {
            files.delete(chat)
            fileRevisions.remove(chat)
            log.i { "plan deleted: chat=$chat" }
        } else if (plan.revision > (fileRevisions[chat] ?: -1)) {
            files.write(chat, plan.text)
            fileRevisions[chat] = plan.revision
            log.d { "plan written: chat=$chat revision=${plan.revision} length=${plan.text.length}" }
        }
    }
}
