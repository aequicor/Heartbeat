package io.aequicor.heartbeat.core.di

/**
 * Creates child scopes. Feature code normally does not call it directly — use the
 * `retainedGraph` / `retainedScope` extensions from `core:di:ext`, which tie the scope to a component.
 */
public interface ScopeFactory {
    /**
     * Creates a scope named `<parent.name>/<name>` whose lifetime is bounded by [parent]: it is closed
     * together with the parent at the latest. [restored] is the state saved before process death.
     *
     * @throws IllegalStateException if [parent] is already closed.
     */
    public fun child(parent: ScopeHandle, name: String, restored: SavedBundle? = null): OwnedScope
}
