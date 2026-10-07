package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Strict storage codec: invalid data propagates, so callers cannot silently discard a restriction. */
internal object HarnessAncestryCodec {
    fun encode(origin: HarnessCallOrigin): String = Json.encodeToString(
        StoredHarnessAncestry(1, origin.isHookRestricted, origin.sendChain),
    )

    fun decode(text: String): HarnessCallOrigin {
        val stored = Json.decodeFromString<StoredHarnessAncestry>(text)
        return HarnessCallOrigin(stored.isHookRestricted, stored.sendChain)
    }
}

@Serializable
private data class StoredHarnessAncestry(
    val version: Int,
    val isHookRestricted: Boolean,
    val sendChain: Map<HarnessId, Int>,
) {
    init {
        require(version == 1) { "Unsupported request ancestry version" }
        require(sendChain.size <= HarnessLimits.HARNESSES) { "Too many request ancestry owners" }
        require(sendChain.values.all { it in 0..HarnessLimits.SEND_CHAIN }) { "Invalid request ancestry depth" }
    }
    override fun toString(): String = "StoredHarnessAncestry(***)"
}
