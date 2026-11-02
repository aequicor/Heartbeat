package io.aequicor.heartbeat.feature.aistudio.impl.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8

/** Work payload only: evolving native checkpoints/candidate hints and transient callbacks are not authority. */
internal fun StudioTurnRequest.recoveryPayloadFingerprint(): String {
    val fields = listOf(
        id,
        request.value,
        prompt,
        settings.modelId,
        settings.effort.name,
        settings.approval.name,
        kind.name,
        Json.encodeToString(attachments),
        Json.encodeToString(directives),
        Json.encodeToString(causes.map { Json.encodeToString(it) }.sorted()),
        submissionId,
        compaction?.instructions,
        compaction?.let { "compact" },
        isRollover.toString(),
        isBriefed.toString(),
        isSummarized.toString(),
        isUserPrompt.toString(),
        userInstruction,
        continuationOf?.value,
        when (val source = origin) {
            StudioTurnOrigin.Conversation -> "conversation"
            StudioTurnOrigin.HelperWork -> "helper"
            StudioTurnOrigin.UnknownService -> "unknown"
            is StudioTurnOrigin.GraphWork -> "graph:${source.recoveryRoot.value}"
        },
    )
    val work = fields + listOfNotNull(graphWork?.let { Json.encodeToString(it) })
    return JsonArray(work.map(::JsonPrimitive)).toString().encodeUtf8().sha256().hex()
}
