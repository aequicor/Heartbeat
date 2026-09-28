package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.Flow

/** Profile-owned local project identity and display name; filesystem paths stay inside the registry. */
public data class LocalWorkspace(val ref: WorkspaceRef, val name: String)

/** Registry of directories explicitly selected by the user, shared by local engine adapters. */
public interface LocalWorkspaces {
    /** Whether this platform supports opening local project directories. */
    public val isAvailable: Boolean

    /** Saved projects, including ones temporarily unavailable on disk. Empty on unsupported platforms. */
    public fun observe(): Flow<List<LocalWorkspace>>

    /**
     * Registers an existing readable absolute directory, reusing its identity after canonicalization.
     * Paths and credentials are never exposed in project metadata or diagnostic messages.
     * @throws IllegalArgumentException when the directory is invalid or unavailable.
     * @throws UnsupportedOperationException when [isAvailable] is false.
     */
    public suspend fun register(directory: String): LocalWorkspace

    /**
     * Resolves a saved identity to its validated local path, or null when unknown or no longer available.
     * Only engine adapters should use this path as a working directory; UI consumes [observe] instead.
     * @throws UnsupportedOperationException when [isAvailable] is false.
     */
    public suspend fun resolve(ref: WorkspaceRef): String?
}
