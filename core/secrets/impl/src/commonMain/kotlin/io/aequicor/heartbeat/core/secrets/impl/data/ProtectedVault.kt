package io.aequicor.heartbeat.core.secrets.impl.data

import io.aequicor.heartbeat.core.secrets.SecretStorageProtection

/** Atomic platform transaction: prevents lost updates; concurrent callers may receive a lock failure. */
internal interface ProtectedVault {
    /** Actual backend metadata; does not access any stored credentials. */
    val protection: SecretStorageProtection get() = SecretStorageProtection.System

    fun <T> transaction(profile: String, erase: Boolean = false, action: (ByteArray?) -> VaultUpdate<T>): T
}

@Suppress("UseDataClass") // Avoid generated copies and toString of sensitive buffers/results.
internal class VaultUpdate<T>(val bytes: ByteArray?, val hasChanges: Boolean, val result: T)
