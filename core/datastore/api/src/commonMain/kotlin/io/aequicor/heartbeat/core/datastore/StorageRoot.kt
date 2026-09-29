package io.aequicor.heartbeat.core.datastore

/**
 * Base directory of every storage of the app; bound per platform (`<Platform>StorageRoot` in core:datastore:impl).
 * Features that keep their own files (e.g. a bundled engine runtime) place them under this directory, so the
 * data shares the application's lifecycle instead of living in ad-hoc home-directory folders.
 */
public fun interface StorageRoot {
    /**
     * Absolute path of the directory. Called once, lazily, on the thread that opens the first storage
     * (possibly the main thread): must be cheap — no blocking IO beyond resolving the platform directory.
     */
    public fun path(): String
}
