package io.aequicor.heartbeat.feature.checklist.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEffect
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistJournal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val JournalSpec = KeyValueSpec("checklist_journal")
private val JournalKey = stringKey("journal")

/** One profile journal; ordered writes prevent slow drafts from overwriting a completion or acknowledgement. */
@SingleIn(ProfileScope::class)
@Inject
internal class ChecklistStorage(
    @ForScope(ProfileScope::class) stores: DataStores,
) {
    private val log = Log.tag("ChecklistStorage")
    private val store = stores.keyValue(JournalSpec)
    private val lock = Mutex()
    private var saved = -1L

    fun observe(): Flow<ChecklistJournal?> {
        log.v { "observe checklist journal" }
        return store.observe(JournalKey).map { it?.let(::decode) }
    }

    suspend fun load(): ChecklistJournal = lock.withLock {
        log.v { "load checklist journal" }
        (store.get(JournalKey)?.let(::decode) ?: ChecklistJournal()).also { saved = it.revision }
    }

    suspend fun save(journal: ChecklistJournal) = lock.withLock {
        log.v { "save checklist revision ${journal.revision}" }
        if (journal.revision > saved) {
            store.set(JournalKey, Json.encodeToString(journal))
            saved = journal.revision
        }
    }
    private fun decode(raw: String): ChecklistJournal = try {
        Json.decodeFromString(raw)
    } catch (e: IllegalArgumentException) {
        throw e.withoutChecklistText()
    }
}

/** Persistence errors are handled by the machine runtime and leave the draft available for Retry. */
@Inject
internal class ChecklistEffects(private val storage: ChecklistStorage) :
    EffectHandler<ChecklistEffect, ChecklistIntent> {
    override suspend fun handle(effect: ChecklistEffect, machine: EffectScope<ChecklistIntent>) {
        when (effect) {
            ChecklistEffect.Load -> machine.send(ChecklistIntent.Internal.Loaded(storage.load()))

            is ChecklistEffect.Save -> {
                storage.save(effect.journal)
                machine.send(ChecklistIntent.Internal.Saved(effect.journal.revision))
            }
        }
    }
}
