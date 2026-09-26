package io.aequicor.heartbeat.core.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle

/**
 * Runtime handle of one DI scope (app, profile, feature, screen, shared object).
 *
 * The handle is bound into its graph as `@ForScope(<Scope>::class) ScopeHandle`. Scopes form a tree.
 * Closing a scope: its coroutines are cancelled (without waiting for them to finish), then close actions run in
 * reverse registration order. A child scope is one of those actions, registered when the child was created:
 * it closes before everything the parent registered earlier and after everything registered later
 * (siblings: the youngest first). Not thread-safe except [onClose]: create and close scopes on the main thread.
 */
public interface ScopeHandle {
    /** Path in the scope tree, e.g. `app/profile/chat`. Used in logs. */
    public val name: String

    /**
     * Coroutines bound to the scope, on the main dispatcher (like `viewModelScope`); switch context for IO/CPU work.
     * Cancelled on close. Failures are logged and do not cancel sibling coroutines.
     */
    public val coroutineScope: CoroutineScope

    /**
     * State that survives process death (see [ScopeSavedState]) — only for scopes owned by a component
     * (`retainedGraph` / `retainedScope`). App, profile and shared scopes are not persisted: what they
     * `register` is lost on process death; the profile itself is restored through `ActiveProfileStorage`.
     */
    public val savedState: ScopeSavedState

    /** `true` once the scope has been closed. */
    public val isClosed: Boolean

    /**
     * Registers [action] to run when the scope closes (actions run in reverse registration order).
     * If the scope is already closed, [action] runs immediately. Dispose the result to unregister early.
     */
    public fun onClose(action: () -> Unit): DisposableHandle
}

/** A scope owned by its creator, who must [close] it. Only [ScopeFactory] creates them. */
public interface OwnedScope :
    ScopeHandle,
    AutoCloseable
