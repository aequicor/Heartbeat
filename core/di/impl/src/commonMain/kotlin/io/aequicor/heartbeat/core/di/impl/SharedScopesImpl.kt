package io.aequicor.heartbeat.core.di.impl

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.Lease
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.SharedFactory
import io.aequicor.heartbeat.core.di.SharedKey
import io.aequicor.heartbeat.core.di.SharedScopes
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class SharedScopesImpl(
    private val factories: Map<String, SharedFactory<*>>,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val parent: ScopeHandle,
) : SharedScopes {

    private val log = Log.tag("SharedScopes")
    private val entries = mutableMapOf<String, Entry>()

    override fun <T : Any> acquire(key: SharedKey<T>): Lease<T> {
        val entry = entries.getOrPut(key.name) { create(key.name) }
        entry.refs++
        log.d { "shared '${key.name}' acquired, refs=${entry.refs}" }
        @Suppress("UNCHECKED_CAST") // factories are keyed by SharedKey.name; the key's type argument is the contract
        return LeaseImpl(entry.value as T) { release(key.name, entry) }
    }

    private fun create(name: String): Entry {
        val factory = requireNotNull(factories[name]) {
            "no SharedFactory contributed to ProfileScope for '$name' (@ContributesIntoMap + @StringKey(\"$name\"))"
        }
        val scope = scopes.child(parent, "shared:$name")
        val value = try {
            factory.create(scope)
        } catch (e: CancellationException) {
            scope.close()
            throw e
        } catch (e: Exception) {
            log.e(e) { "failed to create shared '$name'" }
            scope.close()
            throw e
        }
        return Entry(scope, value)
    }

    private fun release(name: String, entry: Entry) {
        entry.refs--
        log.d { "shared '$name' released, refs=${entry.refs}" }
        if (entry.refs == 0 && entries[name] === entry) {
            entries.remove(name)
            entry.scope.close()
        }
    }

    @Suppress("UseDataClass") // mutable counter compared by identity (`entries[name] === entry`)
    private class Entry(val scope: OwnedScope, val value: Any) {
        var refs = 0
    }

    private class LeaseImpl<T : Any>(override val value: T, private val onRelease: () -> Unit) : Lease<T> {
        private var isReleased = false

        override fun close() {
            if (isReleased) return
            isReleased = true
            onRelease()
        }
    }
}
