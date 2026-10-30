package io.aequicor.heartbeat.buildlogic

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class BootstrapSignedBytes(val signature: ByteArray, val ephemeralPublicKey: String? = null)

/** A release signature must verify with a pinned bootstrap key; launcher-slot keys are never consulted. */
internal fun signBootstrapManifest(
    bytes: ByteArray,
    encodedKey: String?,
    trustedKeys: List<String>,
    allowEphemeral: Boolean,
): BootstrapSignedBytes {
    val encoded = encodedKey?.filterNot(Char::isWhitespace)?.takeIf(String::isNotEmpty)
    check(encoded != null || allowEphemeral) { "HEARTBEAT_BOOTSTRAP_SIGNING_KEY is required for bootstrap packaging" }
    val factory = KeyFactory.getInstance("Ed25519")
    val ephemeral = if (encoded == null) KeyPairGenerator.getInstance("Ed25519").generateKeyPair() else null
    val privateKey = ephemeral?.private
        ?: factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(checkNotNull(encoded))))
    val signature = Signature.getInstance("Ed25519").run {
        initSign(privateKey)
        update(bytes)
        sign()
    }
    if (ephemeral == null) {
        check(trustedKeys.size in 1..4) { "Expected 1 to 4 pinned bootstrap keys" }
        val trusted = trustedKeys.map { factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it))) }
        check(trusted.any { key ->
            Signature.getInstance("Ed25519").run { initVerify(key); update(bytes); verify(signature) }
        }) { "The bootstrap signing key does not match a pinned native launcher key" }
    }
    return BootstrapSignedBytes(signature, ephemeral?.public?.encoded?.let { Base64.getEncoder().encodeToString(it) })
}
