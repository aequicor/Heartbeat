package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioMessageQueues
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueNotice
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Profile queue boundary; the empty implementation keeps non-engine and test runtimes unchanged. */
internal interface StudioQueuePort {
    val state: Flow<StudioMessageQueues> get() = flowOf(StudioMessageQueues())
    val notices: Flow<Map<String, StudioQueueNotice>> get() = flowOf(emptyMap())

    fun hasPending(chat: String): Boolean = false

    suspend fun enqueue(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        isCorrection: Boolean = false,
    ) {
        error("Message queues are unavailable")
    }

    suspend fun correct(chat: String, message: String) {
        error("Steering is unavailable")
    }

    suspend fun remove(chat: String, message: String) = Unit
    suspend fun pause(chat: String) = Unit
    suspend fun resume(chat: String) = Unit
    suspend fun settled(chat: String, request: String, outcome: RunOutcome, isSubmitted: Boolean = true) = Unit
    fun reportFailure(chat: String) = Unit
    fun dismiss(chat: String, notice: String) = Unit
}

/** No retained state, IO or generation in the demo runtime. */
internal object NoStudioQueue : StudioQueuePort
