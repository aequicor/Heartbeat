package io.aequicor.heartbeat.feature.planningmode.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanAuthor
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanEdit
import io.aequicor.heartbeat.feature.planningmode.api.PlanLimits
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanStatus
import io.aequicor.heartbeat.feature.planningmode.api.PlanningEffect
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningOutput
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.applyEdit
import io.aequicor.heartbeat.feature.planningmode.api.externallyEdited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** The planning machine of the profile. */
internal typealias PlanningMachine = Machine<PlanningState, PlanningIntent, PlanningOutput>

/**
 * Executes the effects of the planning machine; failures are mapped by the spec's `onEffectFailure`. Saves run one
 * at a time and a save not newer than the last handled one is dropped, so a late save never brings an older plan
 * back. Every save writes each plan whose revision is newer than the one written last, then the index, then repeats
 * deletions that failed, so a failed write is completed by the next save and a failed delete never blocks the index.
 * Files are written before the index and carry their revision: after an interruption the plan loads with the newer
 * text and its own revision. Files of chats the stored index does not know (deletions that failed before a restart)
 * are deleted on load.
 *
 * A file too long to be a plan loads as an empty plan and is protected: the studio does not write over it until the
 * file is gone, shortened to the studio's own text, taken over by the machine (the agent tools refuse to work from
 * it and ask for it to be shortened) or replaced by an edit the user makes in the studio after loading, which is an
 * explicit choice and must not be lost; a skipped write is logged once per revision.
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

    /** Chats whose file was too long to load, with the revision they loaded at; the studio does not overwrite it. */
    private val tooLong = mutableMapOf<PlanChat, Long>()

    /** The last revision whose write was skipped for a protected chat, so it is checked and logged once. */
    private val skipped = mutableMapOf<PlanChat, Long>()

    override suspend fun handle(effect: PlanningEffect, machine: EffectScope<PlanningIntent>) {
        when (effect) {
            PlanningEffect.Load -> machine.send(load())
            is PlanningEffect.Persist -> saving.withLock { persist(effect) }
        }
    }

    private suspend fun load(): PlanningIntent.Internal.Loaded = saving.withLock {
        val stored = index.load()
        tooLong.clear()
        skipped.clear()
        val records = stored ?: PlanIndex()
        val repaired = mutableSetOf<PlanChat>()
        val plans = records.plans.associate { record ->
            val plan = loaded(record)
            if (plan.revision != record.revision) repaired += record.chat
            record.chat to plan
        }
        if (stored != null) deleteOrphans(plans.keys)
        val bindings = records.bindings.filter { it.chat in plans }.associate { it.session to it.chat }
        log.i { "plans loaded: plans=${plans.size} bindings=${bindings.size} repaired=${repaired.size}" }
        PlanningIntent.Internal.Loaded(plans, bindings, repaired)
    }

    /**
     * [record] joined with its file, compared as an external edit is: trailing whitespace is not a change. An external
     * edit is written again with its new revision; other files are as the studio wrote them.
     */
    private suspend fun loaded(record: Plan): Plan {
        val file = files.read(record.chat)
        if (file == null) {
            fileRevisions[record.chat] = record.revision
            return record.copy(text = "")
        }
        val text = applyEdit(record.text, PlanEdit.Replace(file.text))
        val plan = when {
            file.isOversized || text.length > PlanLimits.MAX_CHARS -> {
                log.w { "plan file too long, loaded empty and protected: chat=${record.chat}" }
                tooLong[record.chat] = record.revision
                record.copy(text = "")
            }

            file.isExternal -> externallyEdited(record, text, clock.now(), file.revision ?: record.revision)

            (file.revision ?: 0) > record.revision -> recovered(record, text, file.revision ?: 0)

            else -> record.copy(text = text)
        }
        if (file.isExternal && record.chat !in tooLong) {
            fileRevisions.remove(record.chat)
        } else {
            fileRevisions[record.chat] = plan.revision
        }
        return plan
    }

    /** Deletes files of chats without a plan in the stored index; a failed delete is repeated by the next save. */
    private suspend fun deleteOrphans(known: Set<PlanChat>) {
        val orphans = try {
            files.chats() - known
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "plan files not listed, orphans kept" }
            emptySet()
        }
        deletions += orphans
        orphans.forEach { delete(it) }
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
        tooLong.keys.retainAll(effect.plans.keys)
        skipped.keys.retainAll(effect.plans.keys)
        effect.plans.values.forEach { plan ->
            if (plan.revision > (fileRevisions[plan.chat] ?: -1) && !isProtected(plan)) {
                files.write(plan.chat, plan.text, plan.revision)
                fileRevisions[plan.chat] = plan.revision
                log.v { "plan written: chat=${plan.chat} revision=${plan.revision} length=${plan.text.length}" }
            }
        }
        val records = effect.plans.values.map { it.copy(text = "") }
        val bindings = effect.bindings.map { (session, chat) -> PlanBinding(session, chat) }
        index.save(PlanIndex(records, bindings))
        log.v { "plan index saved: plans=${records.size} revision=${effect.revision}" }
        deletions.toList().forEach { delete(it) }
    }

    /**
     * Whether the file of [plan] was too long to load and still holds a text the machine has not taken over, so
     * writing [plan] would replace it without the user knowing.
     */
    private suspend fun isProtected(plan: Plan): Boolean {
        val loadedAt = tooLong[plan.chat] ?: return false
        if (skipped[plan.chat] == plan.revision) return true
        val isUserEdit = plan.updatedBy == PlanAuthor.User && plan.revision > loadedAt
        val isTakenOver = isUserEdit || isFileTakenOver(plan)
        if (isTakenOver) {
            tooLong -= plan.chat
            skipped -= plan.chat
            if (isUserEdit) log.i { "protected plan file replaced by the user's edit: chat=${plan.chat}" }
        } else {
            skipped[plan.chat] = plan.revision
            log.w { "plan file too long or changed outside the studio, not overwritten: chat=${plan.chat}" }
        }
        return !isTakenOver
    }

    /** Whether the protected file of [plan] is gone or now holds a usable text the machine already has. */
    private suspend fun isFileTakenOver(plan: Plan): Boolean {
        val file = files.read(plan.chat) ?: return true
        val text = applyEdit(plan.text, PlanEdit.Replace(file.text))
        val isUsable = !file.isOversized && text.length <= PlanLimits.MAX_CHARS
        return isUsable && (!file.isExternal || text == plan.text)
    }

    private suspend fun delete(chat: PlanChat) {
        try {
            files.delete(chat)
            deletions -= chat
            log.i { "plan deleted: chat=$chat" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "plan files not deleted, repeated by the next save: chat=$chat" }
        }
    }
}
