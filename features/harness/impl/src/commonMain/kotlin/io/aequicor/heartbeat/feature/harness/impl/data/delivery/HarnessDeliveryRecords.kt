package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryMarker
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliverySnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Serializable
internal data class HarnessDeliveryRecord(
    val session: SessionRef,
    val generation: String,
    val updatedAt: Instant,
    val activeSetSha: String? = null,
    val markers: List<HarnessDeliveryMarker> = emptyList(),
    val pendingDisabled: Set<HarnessName> = emptySet(),
    val isDeliveryPending: Boolean = false,
) {
    init {
        require(generation.isNotBlank())
        require(activeSetSha == null || isDeliveryDigest(activeSetSha))
        require(markers.size + pendingDisabled.size <= HarnessLimits.ACTIVE_PER_SESSION)
        require(markers.map { it.harness }.distinct().size == markers.size)
        require(markers.map { it.name }.distinct().size == markers.size)
        require(markers.none { it.name in pendingDisabled })
    }

    val expiresAt: Instant get() = updatedAt + DELIVERY_RETENTION
    fun snapshot(): HarnessDeliverySnapshot = HarnessDeliverySnapshot(
        generation,
        activeSetSha,
        markers,
        pendingDisabled,
        isDeliveryPending,
    )
    override fun toString(): String = "HarnessDeliveryRecord(***)"
}

@Serializable
internal data class HarnessDeliveryRecords(
    val entries: List<HarnessDeliveryRecord> = emptyList(),
    val version: Int = 1,
) {
    init {
        require(version == 1 && entries.size <= DELIVERY_SESSIONS)
        require(entries.map { it.session }.distinct().size == entries.size)
    }

    override fun toString(): String = "HarnessDeliveryRecords(***)"
}

internal fun decodeDeliveryRecords(text: String): HarnessDeliveryRecords = try {
    Json.decodeFromString<HarnessDeliveryRecords>(text)
} catch (error: IllegalArgumentException) {
    // Never preserve JSON, source text, names or decoder messages in the exception chain.
    val safe = HarnessStorageCorrupt()
    log.w(safe) { "Harness record decode failed (${error::class.simpleName.orEmpty()})" }
    throw safe
}

internal fun isDeliveryDigest(value: String): Boolean =
    value.length == DELIVERY_DIGEST_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

/** Receipt eviction only requests fresh context delivery; it never grants execution authority. */
internal const val DELIVERY_SESSIONS = 256
internal val DELIVERY_RETENTION = 30.days
private const val DELIVERY_DIGEST_LENGTH = 64

private val log = Log.tag("HarnessDeliveryStorage")
