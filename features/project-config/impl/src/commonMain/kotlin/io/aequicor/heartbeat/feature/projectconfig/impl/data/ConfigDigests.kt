package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

/**
 * SHA-256 of the unit's paths and texts in canonical order. Every path and text is prefixed with its length in bytes,
 * so no text can pass for a file boundary: two units have the same digest only with the same files.
 */
internal val ConfigUnit.digest: ConfigDigest
    get() {
        val buffer = Buffer()
        files.forEach { file ->
            buffer.writeField(file.path.value)
            buffer.writeField(file.text)
        }
        return ConfigDigest(buffer.readByteString().sha256().hex())
    }

private fun Buffer.writeField(value: String) {
    val bytes = value.encodeUtf8()
    writeLong(bytes.size.toLong())
    write(bytes)
}
