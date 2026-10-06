package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.isHarnessLibraryWithinLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessLibrarySnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class HarnessStoredApproval(val level: HarnessApproval = HarnessApproval.Ask, val revision: Long = 0) {
    init {
        require(revision >= 0)
    }
}

/** JSON object keys cannot encode SessionRef safely; storage uses explicit entries and rejects duplicates. */
@Serializable
internal data class HarnessStoredAttachment(val session: SessionRef, val harnesses: Set<HarnessId>)

internal fun harnessRecordKey(id: HarnessId): String = "harness.${id.value.encodeUtf8().sha256().hex()}"

internal fun harnessOperationId(vararg fields: String): String =
    Json.encodeToString(fields.toList()).encodeUtf8().sha256().hex()

internal inline fun <reified T> decodeHarnessRecord(raw: String): T = try {
    Json.decodeFromString<T>(raw)
} catch (error: IllegalArgumentException) {
    // JSON decoder diagnostics may embed source code. The domain exception deliberately has no original cause.
    throw error.withoutHarnessRecordText()
}

internal fun HarnessLibrarySnapshot.isValid(): Boolean =
    approvalRevision >= 0 && isHarnessLibraryWithinLimits(harnesses) && validStoredAttachments(harnesses, attachments)

internal fun validStoredAttachments(harnesses: List<Harness>, attachments: Map<SessionRef, Set<HarnessId>>): Boolean {
    val ids = harnesses.map { it.id }.toSet()
    return attachments.values.all { attached -> attached.isNotEmpty() && attached.all { it in ids } } && ids.all { id ->
        attachments.values.count { id in it } <= HarnessLimits.ATTACHED_PER_HARNESS
    }
}

internal fun Map<SessionRef, Set<HarnessId>>.encodeAttachments(): String = Json.encodeToString(
    entries.map { HarnessStoredAttachment(it.key, it.value) },
)

internal fun decodeAttachments(raw: String?): Map<SessionRef, Set<HarnessId>> {
    if (raw == null) return emptyMap()
    val entries = decodeHarnessRecord<List<HarnessStoredAttachment>>(raw)
    if (entries.map { it.session }.distinct().size != entries.size) throw HarnessStorageCorrupt()
    return entries.associate { it.session to it.harnesses }
}

/** Decoder messages can contain an entire source or answer; retain only its exception kind. */
internal fun Exception.withoutHarnessRecordText(): HarnessStorageCorrupt = HarnessStorageCorrupt(
    this::class.simpleName.orEmpty(),
)
