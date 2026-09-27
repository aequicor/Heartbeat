package io.aequicor.heartbeat.core.secrets.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.impl.domain.SecretSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8

internal class VaultRegistry(private val backend: ProtectedVault, private val dispatchers: DispatcherProvider) {
    private val mutex = Mutex()
    private val codec = VaultCodec()
    private val log = Log.tag("SEC")

    suspend fun <T> access(
        profile: ProfileId,
        operation: String,
        changed: Boolean,
        checkOpen: () -> Unit,
        action: (SecretSnapshot) -> T,
    ): T {
        var undelivered: Secret? = null
        try {
            val delivered = withContext(dispatchers.io) {
                mutex.withLock {
                    checkOpen()
                    log.d { "vault operation=$operation" }
                    guarded {
                        backend.transaction(namespace(profile)) { bytes ->
                            val state = codec.decode(bytes)
                            try {
                                val result = action(state)
                                if (result is Secret) undelivered = result
                                checkOpen()
                                VaultUpdate(
                                    if (changed) codec.encode(state) else null,
                                    changed,
                                    result,
                                )
                            } finally {
                                state.close()
                            }
                        }
                    }
                }
            }

            undelivered = null
            return delivered
        } finally {
            undelivered?.close()
        }
    }

    suspend fun wipe(profile: ProfileId) = withContext(dispatchers.io) {
        mutex.withLock {
            log.i { "vault profile wipe" }
            guarded { backend.transaction(namespace(profile), erase = true) { VaultUpdate(null, true, Unit) } }
        }
    }

    private fun namespace(profile: ProfileId): String {
        require(profile.value.isNotEmpty()) { "Empty profile identifier" }
        return profile.value.encodeUtf8().sha256().hex()
    }

    private inline fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Native/serialization messages can contain plaintext. Never expose their message or cause.
        log.e(error.withoutSensitiveData()) { "vault operation failed" }
        throw error.withoutSensitiveData()
    }
    private fun Exception.withoutSensitiveData(): IllegalStateException =
        IllegalStateException("Protected storage operation failed (${this::class.simpleName ?: "unknown"})")
}
