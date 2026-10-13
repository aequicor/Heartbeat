package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Context of queued remote inputs; the store consumes by identity, so equal inputs from two hosts cannot alias. */
internal class StudioInputContexts {
    private val log = Log.tag("StudioInputContexts")
    private data class Entry(val intent: AiStudioScreenIntent, val environment: StudioChatEnvironment)
    private val pending = MutableStateFlow<List<Entry>>(emptyList())

    @HighFrequency
    fun add(intent: AiStudioScreenIntent, environment: StudioChatEnvironment) {
        log.v { "Queue trusted input context" }
        pending.update { it + Entry(intent, environment) }
    }

    @HighFrequency
    fun take(intent: AiStudioScreenIntent): StudioChatEnvironment? {
        log.v { "Consume trusted input context" }
        while (true) {
            val entries = pending.value
            val index = entries.indexOfFirst { it.intent === intent }
            if (index < 0) return null
            if (pending.compareAndSet(entries, entries.filterIndexed { i, _ -> i != index })) {
                return entries[index].environment
            }
        }
    }
}
