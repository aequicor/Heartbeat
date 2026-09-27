package io.aequicor.heartbeat.core.datastore.impl

/**
 * Base directory of every storage of the app; bound per platform (`<Platform>StorageRoot`).
 * Public so the test graph of `:platform-main:di-bundle` can bind a temporary directory with a higher priority;
 * core:datastore:impl is visible only to the bundle.
 */
public fun interface StorageRoot {
    /**
     * Absolute path of the directory. Called once, lazily, on the thread that opens the first storage
     * (possibly the main thread): must be cheap — no blocking IO beyond resolving the platform directory.
     */
    public fun path(): String
}
