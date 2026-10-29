package io.aequicor.heartbeat.platform.nativelauncher.bootstrap

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** JVM-реализация криптографии bootstrap-протокола на java.security (JDK 17). */
internal actual object PlatformCrypto {
    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun ed25519Verify(publicKeyDer: ByteArray, signature: ByteArray, payload: ByteArray): Boolean {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(publicKeyDer))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(payload)
        return verifier.verify(signature)
    }
}
