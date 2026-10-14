package io.aequicor.heartbeat.core.secrets.impl.data

/**
 * One binary profile snapshot in the persistent default Secret Service collection. The outer JVM vault holds
 * its file lock throughout this transaction. No application file or session-only keyring stores plaintext.
 * Native buffers and borrowed JVM snapshots are cleared even when the callback or persistence fails.
 */
internal class LinuxKeyring(
    private val serviceName: String,
    private val openSession: (String, String) -> LinuxKeyringSession = ::openLibsecretSession,
) {
    fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        val session = try {
            openSession(serviceName, profile)
        } catch (error: LinkageError) {
            // Missing/incompatible libsecret is an unavailable protected backend, never a plaintext fallback.
            throw error.withoutNativeDiagnostics()
        }
        session.use {
            val old = if (erase) null else session.read()
            try {
                val update = action(old)
                try {
                    if (update.hasChanges) session.write(update.bytes)
                    return update.result
                } finally {
                    update.bytes?.fill(0)
                }
            } finally {
                old?.fill(0)
            }
        }
    }

    private fun LinkageError.withoutNativeDiagnostics(): ProtectedVaultAccessException =
        ProtectedVaultAccessException(VaultAccessProblem.LinuxKeyringUnavailable)
}

/** Owned Secret Service connection for one profile; opening must reject missing or locked collections. */
internal interface LinuxKeyringSession : AutoCloseable {
    fun read(): ByteArray?
    fun write(bytes: ByteArray?)
}
