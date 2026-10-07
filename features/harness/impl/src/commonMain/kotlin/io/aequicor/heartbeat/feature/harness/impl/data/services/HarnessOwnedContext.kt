package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Host-created ownership and ancestry, persisted with a wake or carried by a feature bus origin. This codec is
 * never applied to script arguments, event payloads or ordinary session identifiers. Missing/malformed context
 * is a refusal, not neutral ancestry. Format version is independent of script compilation API version.
 */
internal class HarnessOwnedContext {
    private val json = Json
    private val log = Log.tag("HarnessServices")

    fun encode(harness: HarnessId, origin: HarnessCallOrigin): String = json.encodeToString(
        HarnessOwnership.serializer(),
        HarnessOwnership(1, harness, origin.isHookRestricted, origin.sendChain),
    ).also { require(it.length <= CONTEXT_CHARS) { "Harness ownership context is too large" } }

    @HighFrequency
    fun decode(value: String?): HarnessOwnership? {
        if (value == null || value.length > CONTEXT_CHARS) return null
        return try {
            json.decodeFromString(HarnessOwnership.serializer(), value)
        } catch (error: SerializationException) {
            log.w(harnessScriptFailure(error)) { "Reject malformed ownership" }
            null
        } catch (error: IllegalArgumentException) {
            log.w(harnessScriptFailure(error)) { "Reject invalid ownership" }
            null
        }
    }
}

@Serializable
internal data class HarnessOwnership(
    val version: Int,
    val harness: HarnessId,
    val isHookRestricted: Boolean,
    val sendChain: Map<HarnessId, Int>,
) {
    init {
        require(version == 1) { "Unsupported ownership version" }
        require(sendChain.size <= HarnessLimits.HARNESSES) { "Too many origin owners" }
        require(sendChain.values.all { it in 0..HarnessLimits.SEND_CHAIN }) { "Invalid origin depth" }
    }

    fun origin(): HarnessCallOrigin = HarnessCallOrigin(isHookRestricted, sendChain)
    override fun toString(): String = "HarnessOwnership(***)"
}

private const val CONTEXT_CHARS = 4096
