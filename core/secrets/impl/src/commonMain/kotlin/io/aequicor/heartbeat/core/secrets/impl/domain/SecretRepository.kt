package io.aequicor.heartbeat.core.secrets.impl.domain

/**
 * Profile-local atomic unit of work. The callback must not retain the snapshot or its buffers.
 * Updates commit values and references together. Callback failure before commit leaves storage unchanged.
 * Cancellation during result delivery does not roll back a commit that has already completed.
 * Implementations erase snapshot buffers after the callback and close undelivered Secret results.
 */
internal interface SecretRepository {
    suspend fun <T> transaction(hasChanges: Boolean = false, action: (SecretSnapshot) -> T): T
}
