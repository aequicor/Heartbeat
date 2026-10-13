package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.Base64BytesSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * Encodes a watch as bounded transport messages. A frame over [maxBytes] produces a size notice and the next
 * acceptable state starts with a snapshot. The watch remains alive; no unreceived revision is acknowledged.
 */
class StudioViewWriter(generation: String = Uuid.random().toString(), private val maxBytes: Int = MAX_VIEW_BYTES) {
    private val encoder = StudioViewEncoder(generation = generation)

    init {
        require(maxBytes in 1..MAX_VIEW_BYTES)
    }

    /** Packets for [state], or none if the screen and acknowledgement have not changed. */
    @HighFrequency
    fun write(state: AiStudioScreenState, ack: Long): Sequence<JsonObject> {
        val frame = encoder.frame(state, ack) ?: return emptySequence()
        val json = StudioViewJson.encodeToJsonElement(StudioViewFrame.serializer(), frame).jsonObject
        val bytes = json.toString().encodeToByteArray()
        if (bytes.size > maxBytes) {
            encoder.reset()
            return sequenceOf(
                StudioViewJson.encodeToJsonElement(StudioViewLimit.serializer(), StudioViewLimit(maxBytes)).jsonObject,
            )
        }
        return packets(json, bytes)
    }
}

/** Small frames retain their ordinary wire form; larger ones are atomic sequences of binary slices. */
private fun packets(json: JsonObject, bytes: ByteArray): Sequence<JsonObject> {
    if (bytes.size <= VIEW_PART_BYTES) return sequenceOf(json)
    val transfer = Uuid.random().toString()
    val count = (bytes.size + VIEW_PART_BYTES - 1) / VIEW_PART_BYTES
    return (0 until count).asSequence().map { index ->
        val start = index * VIEW_PART_BYTES
        val packet = StudioViewPacket(
            transfer,
            index,
            count,
            bytes.size,
            bytes.copyOfRange(start, minOf(start + VIEW_PART_BYTES, bytes.size)),
        )
        StudioViewJson.encodeToJsonElement(StudioViewPacket.serializer(), packet).jsonObject
    }
}

/** Ordered, bounded assembly for one watch. A cancelled or restarted watch must call [reset]. */
class StudioViewReader {
    private var transfer: String? = null
    private var content: ByteArray? = null
    private var nextPart = 0

    /** Discards an unfinished frame from the previous watch. */
    fun reset() {
        transfer = null
        content = null
        nextPart = 0
    }

    /**
     * Returns a whole frame, or null while receiving its parts. Malformed, repeated and out-of-order parts throw
     * [IllegalArgumentException]; the host must reset the watch instead of applying an incomplete screen.
     * [StudioViewLimitException] keeps the watch alive but suspends commands until a new snapshot arrives.
     */
    @HighFrequency
    fun read(json: JsonObject): StudioViewFrame? {
        if ("tooLarge" in json) {
            val notice = StudioViewJson.decodeFromJsonElement(StudioViewLimit.serializer(), json)
            require(notice.tooLarge in 1..MAX_VIEW_BYTES) { "Invalid studio frame limit" }
            reset()
            throw StudioViewLimitException(notice.tooLarge)
        }
        if ("transfer" !in json) {
            require(transfer == null) { "Incomplete studio frame" }
            return StudioViewJson.decodeFromJsonElement(StudioViewFrame.serializer(), json)
        }
        val part = StudioViewJson.decodeFromJsonElement(StudioViewPacket.serializer(), json)
        require(part.totalBytes in 1..MAX_VIEW_BYTES) { "Invalid studio frame size" }
        require(part.count == (part.totalBytes + VIEW_PART_BYTES - 1) / VIEW_PART_BYTES) { "Invalid part count" }
        require(part.index in 0 until part.count) { "Invalid part index" }
        if (transfer == null) {
            require(part.index == 0) { "Missing first studio part" }
            transfer = part.transfer
            content = ByteArray(part.totalBytes)
        }
        val bytes = checkNotNull(content)
        require(part.transfer == transfer && bytes.size == part.totalBytes && part.index == nextPart) {
            "Studio parts do not continue the frame"
        }
        val start = part.index * VIEW_PART_BYTES
        require(part.data.size == minOf(VIEW_PART_BYTES, bytes.size - start)) { "Invalid part size" }
        part.data.copyInto(bytes, start)
        nextPart++
        return if (nextPart == part.count) {
            reset()
            StudioViewJson.decodeFromString(
                StudioViewFrame.serializer(),
                bytes.decodeToString(throwOnInvalidSequence = true),
            )
        } else {
            null
        }
    }
}

/** The view exceeded [maxBytes]; keep the watch and wait for its next acceptable snapshot. */
class StudioViewLimitException(val maxBytes: Int) : IllegalStateException("Studio view exceeds $maxBytes bytes")

/** A size refusal contains no frame revision or acknowledgement. */
@Serializable
internal data class StudioViewLimit(val tooLarge: Int)

/** Part of a UTF-8 frame. Binary slices preserve Unicode even when a boundary falls inside a code point. */
@Serializable
internal data class StudioViewPacket(
    val transfer: String,
    val index: Int,
    val count: Int,
    val totalBytes: Int,
    @Serializable(with = Base64BytesSerializer::class) val data: ByteArray,
)

/** Base64 expands one part to about 350 KiB, below the bridge's 1 MB message limit with ample envelope space. */
private const val VIEW_PART_BYTES = 256 * 1024

/** Maximum allocation of a single in-progress logical frame, independent of the native bridge's 16 MiB limit. */
private const val MAX_VIEW_BYTES = 128 * 1024 * 1024
