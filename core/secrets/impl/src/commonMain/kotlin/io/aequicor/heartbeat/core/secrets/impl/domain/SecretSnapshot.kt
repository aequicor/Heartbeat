package io.aequicor.heartbeat.core.secrets.impl.domain

import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretUsage

@Suppress("UseDataClass") // Generated toString/copy must not expose or share sensitive buffers.
internal class SecretSnapshot(
    val values: MutableMap<SecretKey, CharArray> = mutableMapOf(),
    val references: MutableMap<SecretUsage, SecretKey> = mutableMapOf(),
) : AutoCloseable {
    override fun close() {
        values.values.forEach { it.fill('\u0000') }
    }
}
