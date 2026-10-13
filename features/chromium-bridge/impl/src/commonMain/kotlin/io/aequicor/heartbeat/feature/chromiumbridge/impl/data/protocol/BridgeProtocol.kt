package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import kotlinx.serialization.json.Json

/**
 * Wire protocol shared by the extension, the native-messaging host and the app.
 *
 * Every message is one UTF-8 JSON object prefixed by its 4-byte little-endian length. Chrome uses that framing
 * between the extension and the host; the host reuses it on the app socket, so it copies frames without parsing
 * them. Versioning is negotiated once in `hello`; changes inside a version are additive and unknown fields are
 * ignored on both sides.
 */
internal object BridgeProtocol {
    /** Protocol version the app speaks. */
    const val VERSION: Int = 1

    /** Native-messaging host name registered in every browser. */
    const val HOST_NAME: String = "io.aequicor.heartbeat"

    /** Chrome's hard cap for a host-to-extension message; a larger frame makes Chrome close the port. */
    const val MAX_TO_BROWSER_BYTES: Int = 1_000_000

    /**
     * Raw bytes of a serialized frame carried by one chunk. Base64 grows it by 4/3 and the chunk envelope stays
     * small, so a chunk frame stays well below [MAX_TO_BROWSER_BYTES].
     */
    const val CHUNK_SLICE_BYTES: Int = 600_000

    /** Cap for one frame from the browser; screenshots are the largest legitimate payload. */
    const val MAX_FROM_BROWSER_BYTES: Int = 16 * 1024 * 1024

    /** JSON format of every frame: `kind` discriminates the envelope, absent optional fields are omitted. */
    val json: Json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}
