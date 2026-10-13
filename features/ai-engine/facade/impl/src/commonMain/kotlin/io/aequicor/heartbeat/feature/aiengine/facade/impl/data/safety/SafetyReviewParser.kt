package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json

/** Rejects missing fields, unknown verdicts, prose, fences and truncated or oversized answers. */
internal fun parseSafetyReview(answer: String): AgentSafetyReview {
    if (answer.length > MAX_REVIEW_ANSWER) return invalidReview()
    val review = try {
        Json.decodeFromString(UniqueAgentSafetyReview, answer)
    } catch (e: IllegalArgumentException) {
        reviewLog.w(
            IllegalArgumentException(e::class.simpleName.orEmpty()),
        ) { "Safety review did not match the schema" }
        return invalidReview()
    }
    return review.takeIf {
        it.verdict in setOf("safe", "risk", "uncertain") && it.reason.isNotBlank() && it.reason.length <= MAX_REASON
    } ?: invalidReview()
}

private fun invalidReview() = AgentSafetyReview("uncertain", "Автоматическое ревью не дало однозначного вердикта.")
private val reviewLog = Log.tag("AgentSafetyReview")
private const val MAX_REVIEW_ANSWER = 4096
private const val MAX_REASON = 2000

/** Reads fields directly: a JSON tree would already have discarded duplicate keys. */
private object UniqueAgentSafetyReview : KSerializer<AgentSafetyReview> {
    override val descriptor = buildClassSerialDescriptor("UniqueAgentSafetyReview") {
        element<String>("verdict")
        element<String>("reason")
    }

    override fun deserialize(decoder: Decoder): AgentSafetyReview = decoder.decodeStructure(descriptor) {
        val fields = mutableMapOf<Int, String>()
        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            if (index !in 0..1 || index in fields) throw SerializationException("Ambiguous review fields")
            fields[index] = decodeStringElement(descriptor, index)
        }
        AgentSafetyReview(
            fields[0] ?: throw SerializationException("Missing verdict"),
            fields[1] ?: throw SerializationException("Missing reason"),
        )
    }

    override fun serialize(encoder: Encoder, value: AgentSafetyReview) =
        throw SerializationException("Review decoder is read-only")
}
