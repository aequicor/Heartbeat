package io.aequicor.heartbeat.core.secrets.impl

/** Atomic platform transaction: prevents lost updates; concurrent callers may receive a lock failure. */
internal interface ProtectedVault {
    fun <T> transaction(profile: String, erase: Boolean = false, action: (ByteArray?) -> VaultUpdate<T>): T
}

@Suppress("UseDataClass") // Avoid generated copies and toString of sensitive buffers/results.
internal class VaultUpdate<T>(val bytes: ByteArray?, val hasChanges: Boolean, val result: T)
