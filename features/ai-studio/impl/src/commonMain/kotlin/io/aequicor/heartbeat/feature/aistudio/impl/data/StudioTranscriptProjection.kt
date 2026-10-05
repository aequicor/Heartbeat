package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/** Joins durable history, inline cards and runtime state without tying observation to the active pane. */
internal class StudioTranscriptProjection(
    private val store: KeyValueStore,
    private val transcripts: StudioTranscripts,
    private val configurations: StudioConfigurationController,
    private val checklists: StudioChecklists,
) {
    fun observe(sessionId: String, state: Flow<StudioRuntimeState>): Flow<List<StudioMessage>> = combine(
        transcripts.observe(sessionId),
        state,
        record(sessionId),
        configurations.feedback(sessionId),
        checklists.events,
    ) { stored, runtime, record, feedback, checklistEvents ->
        stored.toStudioMessages(record.updatedAt, sessionId in runtime.running, feedback)
            .withChecklists(checklistEvents.filter { it.session == record.ref }) +
            if (record.hasFailed) {
                listOf(StudioMessage.Failed("failure", record.updatedAt, record.failureKind))
            } else {
                emptyList()
            }
    }

    private fun record(id: String): Flow<StudioChatRecord> = store.observe(ChatsKey)
        .map { records ->
            records.orEmpty().firstOrNull { it.id == id } ?: StudioChatRecord(id, "", Instant.DISTANT_PAST)
        }.distinctUntilChanged()
}
