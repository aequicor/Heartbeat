package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** Native measurements of one process generation; estimated session stats never enter this stream. */
internal class PiSessionUsage(private val environment: PiSessionEnvironment, handle: ScopeHandle) :
    SessionContextUsage {
    private val log = Log.tag("PiUsage")
    private val mutableState = MutableStateFlow<ContextUsage?>(null)
    override val state = mutableState.asStateFlow()
    private var model: JsonObject? = null
    private var capacity: Long? = null
    private var isClosed = false

    init {
        handle.coroutineScope.launch {
            environment.toggles.observe(EngineUsageEnabled).collect { enabled -> if (!enabled) clear() }
        }
    }

    fun model(value: JsonObject?, confirmedCapacity: Long?) {
        model = value
        capacity = confirmedCapacity
        clear()
    }

    fun clear() {
        log.v { "Clear native context observation" }
        mutableState.value = null
    }

    fun close() {
        isClosed = true
        clear()
    }

    suspend fun event(record: JsonObject) {
        when (record.string("type")) {
            "auto_compaction_start", "auto_compaction_end", "compaction_start", "compaction_end" -> clear()

            "message_end" -> {
                val message = record["message"] as? JsonObject
                if (message?.string("role") == "assistant" && !isClosed) {
                    log.v { "Native context observation received" }
                    mutableState.value = if (environment.toggles.get(EngineUsageEnabled)) {
                        piContextUsage(message, model, Clock.System.now(), capacity)
                    } else {
                        null
                    }
                }
            }
        }
    }
}
