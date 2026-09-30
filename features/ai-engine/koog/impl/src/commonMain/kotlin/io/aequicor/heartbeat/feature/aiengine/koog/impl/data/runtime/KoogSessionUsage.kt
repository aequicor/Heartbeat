package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.ResponseMetaInfo
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Replaces the last request's occupancy on each tool round; never sums billing over a turn or conversation. */
internal class KoogSessionUsage(private val access: KoogAccess, scope: CoroutineScope) : SessionContextUsage {
    private val log = Log.tag("KoogSessionUsage")
    private val value = MutableStateFlow<ContextUsage?>(null)
    override val state = value.asStateFlow()
    private var capture: KoogUsageCapture? = null
    private val observation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        access.usageEnabledChanges.collect { enabled ->
            capture?.enable(enabled)
            if (!enabled) {
                value.value = null
                log.d { "Context usage cleared while telemetry is disabled" }
            }
        }
    }

    suspend fun start(client: KoogClient) {
        capture = client.usage
        client.usage.reset()
        client.usage.enable(access.usageEnabled())
    }

    suspend fun complete(
        provider: LLMProvider,
        model: String,
        connection: KoogConnection,
        client: KoogClient,
        metadata: ResponseMetaInfo,
    ) {
        if (!access.usageEnabled()) return
        val tokens = if (provider == LLMProvider.Anthropic) {
            client.usage.total
        } else {
            metadata.totalTokensCount?.toLong()?.takeIf { it >= 0 }
                ?: nativeTokenSum(metadata.inputTokensCount?.toLong(), metadata.outputTokensCount?.toLong())
        }
        val capacity = tokens?.let { access.contextWindows.resolve(connection, model, client) }
        value.value = if (tokens != null && capacity != null && access.usageEnabled()) {
            ContextUsage(tokens, capacity, Observation(Clock.System.now(), isStale = false))
        } else {
            null
        }
        log.d { "Context usage observation available=${value.value != null}" }
    }

    fun close() {
        observation.cancel()
        capture?.enable(false)
        capture = null
        clear()
    }

    fun clear() {
        value.value = null
        log.d { "Context usage observation cleared" }
    }
}
