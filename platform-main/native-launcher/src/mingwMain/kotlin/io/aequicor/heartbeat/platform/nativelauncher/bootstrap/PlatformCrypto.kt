package io.aequicor.heartbeat.platform.nativelauncher.bootstrap

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.signature.Signature
import io.aequicor.heartbeat.platform.nativelauncher.launcherLog
import kotlinx.coroutines.CancellationException

/** Native-реализация криптографии bootstrap-протокола на libsodium (Ed25519, SHA-256). */
internal actual object PlatformCrypto {
    private const val X509_KEY_SIZE = 44
    private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    private fun initialize() {
        if (!LibsodiumInitializer.isInitialized()) {
            var isDone = false
            LibsodiumInitializer.initializeWithCallback { isDone = true }
            check(isDone) { "Libsodium initialization did not complete synchronously" }
        }
    }

    actual fun sha256(data: ByteArray): ByteArray {
        initialize()
        return Hash.sha256(data.toUByteArray()).toByteArray()
    }

    actual fun ed25519Verify(publicKeyDer: ByteArray, signature: ByteArray, payload: ByteArray): Boolean {
        initialize()
        require(
            publicKeyDer.size == X509_KEY_SIZE && publicKeyDer.copyOfRange(
                0,
                X509_PREFIX.size,
            ).contentEquals(X509_PREFIX),
        ) {
            "Not an Ed25519 X.509 public key"
        }
        val rawKey = publicKeyDer.copyOfRange(X509_PREFIX.size, X509_KEY_SIZE).toUByteArray()
        return try {
            Signature.verifyDetached(signature.toUByteArray(), payload.toUByteArray(), rawKey)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            launcherLog.w("Bootstrap", error) { "Signature verification failed" }
            false
        }
    }
}
