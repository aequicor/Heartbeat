package io.aequicor.heartbeat.platform.nativelauncher.bootstrap

import io.aequicor.heartbeat.core.logging.Log

/**
 * Проверка подписи bootstrap-манифеста: detached Ed25519-подпись (64 байта) ровно от байтов JSON манифеста.
 * Доверенные публичные ключи — X.509 SubjectPublicKeyInfo (44 байта для Ed25519), отдельный release-ключ,
 * не ключ слотов лончера. Проверка выполняется до разбора манифеста.
 */
object BootstrapSignature {
    const val SIGNATURE_BYTES = 64

    private val log = Log.tag("Bootstrap")

    /** true, если подпись [signature] байтов [manifestBytes] сделана ключом из [trustedKeysDer]. */
    fun verify(manifestBytes: ByteArray, signature: ByteArray, trustedKeysDer: List<ByteArray>): Boolean {
        if (signature.size != SIGNATURE_BYTES || trustedKeysDer.isEmpty()) return false
        return trustedKeysDer.any { key ->
            runCatching { PlatformCrypto.ed25519Verify(key, signature, manifestBytes) }
                .onFailure { log.w(it) { "Bootstrap signature verification failed" } }
                .getOrDefault(false)
        }
    }
}

private const val HEX_NIBBLE_BITS = 4

/** SHA-256 содержимого в нижнем hex: проверка артефактов после скачивания. */
fun sha256Hex(data: ByteArray): String {
    val digits = "0123456789abcdef"
    val digest = PlatformCrypto.sha256(data)
    return buildString(digest.size * 2) {
        for (byte in digest) {
            append(digits[(byte.toInt() ushr HEX_NIBBLE_BITS) and 0xf])
            append(digits[byte.toInt() and 0xf])
        }
    }
}

/** Платформенная криптография: JVM — java.security, нативные реализации добавляются вместе с лончером. */
internal expect object PlatformCrypto {
    fun sha256(data: ByteArray): ByteArray
    fun ed25519Verify(publicKeyDer: ByteArray, signature: ByteArray, payload: ByteArray): Boolean
}
