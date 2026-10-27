package io.aequicor.heartbeat.core.secrets.impl.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the last snapshot of each profile in memory, so [delegate] (the macOS Keychain, which asks the user for
 * confirmation at every read of an item its ACL does not trust) is read once per profile and launch; later
 * transactions read the copy and only write changes. The copy lives until the process exits: that trades exposure of
 * the decrypted snapshot to this process's memory for fewer system prompts. The caller's lock per profile (the vault
 * lock file) serialises transactions. The copy is not refreshed from outside: a change made meanwhile by another
 * process or by Keychain Access stays unseen until restart and is overwritten by the next write, so a second running
 * instance on the same Keychain service is not supported. A snapshot is remembered only after the backend took the
 * write; a profile with no stored value is not cached and asks the backend each time.
 */
internal class CachedVault(private val delegate: ProtectedVault) : ProtectedVault {
    private val snapshots = ConcurrentHashMap<String, ByteArray>()

    override val protection get() = delegate.protection

    override fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        val cached = if (erase) null else snapshots[profile]
        if (cached == null) return read(profile, erase, action)
        val lent = cached.copyOf()
        val update = try {
            action(lent)
        } finally {
            lent.fill(0)
        }
        return try {
            if (update.hasChanges) {
                delegate.transaction(profile, erase = true) { VaultUpdate(update.bytes?.copyOf(), true, Unit) }
                remember(profile, update.bytes)
            }
            update.result
        } finally {
            update.bytes?.fill(0)
        }
    }

    private fun <T> read(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        if (erase) forget(profile)
        // What the profile holds once this transaction is written; known only after the backend accepted it.
        var committed: ByteArray? = null
        var hasCommitted = false
        try {
            return delegate.transaction(profile, erase) { old ->
                val seen = old?.copyOf()
                val update = action(old)
                // The delegate wipes the buffers it lent; the snapshot is kept from copies taken before.
                committed = if (update.hasChanges) update.bytes?.copyOf() else seen
                hasCommitted = true
                if (update.hasChanges) seen?.fill(0)
                update
            }.also { remember(profile, committed) }
        } finally {
            if (hasCommitted) committed?.fill(0)
        }
    }

    private fun forget(profile: String) {
        snapshots.remove(profile)?.fill(0)
    }

    private fun remember(profile: String, bytes: ByteArray?) {
        val previous = if (bytes == null) snapshots.remove(profile) else snapshots.put(profile, bytes.copyOf())
        previous?.fill(0)
    }
}
