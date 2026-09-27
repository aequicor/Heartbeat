package io.aequicor.heartbeat.core.secrets.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

@SingleIn(AppScope::class)
@Inject
internal class VaultRegistry(private val backend: ProtectedVault, private val dispatchers: DispatcherProvider) {
    private val mutex = Mutex()
    private val log = Log.tag("SEC")

    suspend fun <T> access(
        profile: ProfileId,
        operation: String,
        changed: Boolean,
        checkOpen: () -> Unit,
        action: (VaultState) -> T,
    ): T {
        var undelivered: Secret? = null
        try {
            val delivered = withContext(dispatchers.io) {
                mutex.withLock {
                    checkOpen()
                    log.d { "vault operation=$operation" }
                    guarded {
                        backend.transaction(namespace(profile)) { bytes ->
                            val state = decode(bytes)
                            try {
                                val result = action(state)
                                if (result is Secret) undelivered = result
                                checkOpen()
                                VaultUpdate(
                                    if (changed) Json.encodeToString(state).encodeToByteArray() else null,
                                    changed,
                                    result,
                                )
                            } finally {
                                state.values.values.forEach { it.fill('\u0000') }
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

    private fun decode(bytes: ByteArray?): VaultState {
        val state = if (bytes == null) VaultState() else Json.decodeFromString<VaultState>(bytes.decodeToString())
        check(state.version == 1) { "Unsupported vault version" }
        check(state.references.all { it.key in state.values }) { "Invalid vault references" }
        return state
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

@Serializable
@Suppress("UseDataClass") // Generated copies and toString must not expose sensitive values.
internal class VaultState(
    val version: Int = 1,
    val values: MutableMap<String, CharArray> = mutableMapOf(),
    val references: MutableList<VaultReference> = mutableListOf(),
)

@Serializable
internal data class VaultReference(val type: String, val id: String, val slot: String, val key: String) {
    fun usage(): SecretUsage = SecretUsage(type, id, slot)
}
