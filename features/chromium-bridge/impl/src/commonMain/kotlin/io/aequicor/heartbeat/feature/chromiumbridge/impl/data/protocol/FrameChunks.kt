package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import kotlin.io.encoding.Base64

/**
 * Splits a serialized frame into [BridgeFrame.Chunk]s of at most [sliceBytes] raw bytes each. Slicing raw UTF-8
 * bytes and base64-encoding every slice keeps each chunk's size predictable regardless of escaping, and a
 * multi-byte character split between slices is restored when the receiver joins the bytes before decoding.
 */
internal fun chunkFrame(
    setId: String,
    payload: ByteArray,
    sliceBytes: Int = BridgeProtocol.CHUNK_SLICE_BYTES,
): List<BridgeFrame.Chunk> {
    require(sliceBytes > 0) { "Slice size must be positive" }
    val count = maxOf(1, (payload.size + sliceBytes - 1) / sliceBytes)
    return List(count) { seq ->
        val start = seq * sliceBytes
        val end = minOf(payload.size, start + sliceBytes)
        BridgeFrame.Chunk(of = setId, seq = seq, count = count, data = Base64.encode(payload, start, end))
    }
}

/**
 * Joins [BridgeFrame.Chunk]s back into frame payloads. Rejects inconsistent sets (changing count, out-of-range or
 * repeated sequence numbers) and sets whose decoded size exceeds [maxBytes]; at most [maxSets] sets are open.
 * Not thread-safe: one assembler belongs to one reading loop.
 */
internal class ChunkAssembler(
    private val maxBytes: Int = BridgeProtocol.MAX_FROM_BROWSER_BYTES,
    private val maxSets: Int = MAX_OPEN_SETS,
) {
    private val sets = LinkedHashMap<String, Partial>()

    /** Number of incomplete sets, for diagnostics and tests. */
    val pending: Int get() = sets.size

    /**
     * Adds [chunk]; returns the whole payload once its set is complete, otherwise `null`.
     * @throws IllegalArgumentException when the chunk breaks its set; the set is dropped.
     */
    fun accept(chunk: BridgeFrame.Chunk): ByteArray? {
        require(chunk.count in 1..MAX_CHUNKS && chunk.seq in 0 until chunk.count) { "Chunk out of range" }
        val partial = sets[chunk.of] ?: Partial(chunk.count).also {
            if (sets.size >= maxSets) sets.remove(sets.keys.first())
            sets[chunk.of] = it
        }
        try {
            require(partial.count == chunk.count) { "Chunk count changed" }
            require(partial.slices[chunk.seq] == null) { "Chunk repeated" }
            val bytes = Base64.decode(chunk.data)
            partial.size += bytes.size
            require(partial.size <= maxBytes) { "Chunked frame too large" }
            partial.slices[chunk.seq] = bytes
        } catch (e: IllegalArgumentException) {
            sets.remove(chunk.of)
            throw e
        }
        if (!partial.isComplete()) return null
        sets.remove(chunk.of)
        val result = ByteArray(partial.size)
        var offset = 0
        partial.slices.forEach { slice ->
            val bytes = checkNotNull(slice)
            bytes.copyInto(result, offset)
            offset += bytes.size
        }
        return result
    }

    /** Slices received so far; [size] is their decoded total. */
    private class Partial(val count: Int) {
        val slices: Array<ByteArray?> = arrayOfNulls(count)
        var size: Int = 0

        fun isComplete(): Boolean = slices.all { it != null }
    }

    private companion object {
        const val MAX_OPEN_SETS = 4
        const val MAX_CHUNKS = 64
    }
}
