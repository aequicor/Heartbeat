package io.aequicor.heartbeat.core.di.ext

import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.InstanceKeeperOwner
import com.arkivanov.essenty.instancekeeper.getOrCreate
import com.arkivanov.essenty.statekeeper.StateKeeperOwner
import io.aequicor.heartbeat.core.di.Lease
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.SharedKey
import io.aequicor.heartbeat.core.di.SharedScopes
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException

/** Default key of [retainedGraph]; pass another one to retain several graphs in one component. */
public const val DEFAULT_GRAPH_KEY: String = "scope-graph"

/** Default key of [retainedScope]. */
public const val DEFAULT_SCOPE_KEY: String = "screen-scope"

/**
 * Returns the graph of a child scope of [parent], retained for the lifetime of this component:
 * the same instance after a configuration change, recreated with restored [ScopeHandle.savedState]
 * after process death, closed when the component is destroyed.
 *
 * [create] runs only when the graph is (re)created — values it captures from the first call are kept, and so are
 * [parent] and [name]: later calls with other values get the retained graph. Use a distinct [key] per retained
 * object in one component (`retainedGraph` and `retainedScope` must not share a key).
 * Throws `IllegalStateException` if [parent] is already closed.
 *
 * ```
 * override fun create(ctx: ComponentContext, args: ChatArgs): ChatComponent =
 *     ctx.retainedGraph(scopes, profileScope, name = "chat") { scope -> graphs.create(args, scope) }
 *         .rootFactory.create(ctx)
 * ```
 */
public fun <O, G : Any> O.retainedGraph(
    scopes: ScopeFactory,
    parent: ScopeHandle,
    name: String,
    key: String = DEFAULT_GRAPH_KEY,
    create: (ScopeHandle) -> G,
): G where O : InstanceKeeperOwner, O : StateKeeperOwner = retainInScope(scopes, parent, name, key, create)

/**
 * Screen scope without its own graph: a [ScopeHandle] retained like [retainedGraph]. Pass it to
 * assisted factories (stores, screen-level holders) that need coroutines or saved state.
 */
public fun <O> O.retainedScope(
    scopes: ScopeFactory,
    parent: ScopeHandle,
    name: String,
    key: String = DEFAULT_SCOPE_KEY,
): ScopeHandle where O : InstanceKeeperOwner, O : StateKeeperOwner =
    retainInScope(scopes, parent, name, key) { scope -> scope }

/**
 * Holds the shared object [key] while this component exists (configuration changes included);
 * the hold is released when the component is destroyed.
 */
public fun <T : Any> InstanceKeeperOwner.retainedShared(shared: SharedScopes, key: SharedKey<T>): T =
    instanceKeeper.getOrCreate(key = "shared:${key.name}") { LeaseHolder(shared.acquire(key)) }.lease.value

private fun <O, T : Any> O.retainInScope(
    scopes: ScopeFactory,
    parent: ScopeHandle,
    name: String,
    key: String,
    create: (ScopeHandle) -> T,
): T where O : InstanceKeeperOwner, O : StateKeeperOwner {
    val holder = instanceKeeper.getOrCreate(key) {
        val scope = scopes.child(parent, name, restored = stateKeeper.consume(key, SavedBundle.serializer()))
        ScopeHolder(scope, createInScope(scope, create))
    }
    // Registered on EVERY component instance, not only when the holder is created: after a configuration
    // change the component (and its StateKeeper) is new while the holder is retained. Registering only
    // once would silently lose the scope state on the next process death.
    if (!stateKeeper.isRegistered(key)) {
        stateKeeper.register(key, SavedBundle.serializer()) { holder.scope.savedState.snapshot() }
    }
    return holder.value
}

private fun <T : Any> createInScope(scope: OwnedScope, create: (ScopeHandle) -> T): T = try {
    create(scope)
} catch (e: CancellationException) {
    scope.close()
    throw e
} catch (e: Exception) {
    Log.tag("DI").e(e) { "failed to create an object in scope ${scope.name}" }
    scope.close()
    throw e
}

private class ScopeHolder<T : Any>(val scope: OwnedScope, val value: T) : InstanceKeeper.Instance {
    override fun onDestroy() = scope.close()
}

private class LeaseHolder<T : Any>(val lease: Lease<T>) : InstanceKeeper.Instance {
    override fun onDestroy() = lease.close()
}
