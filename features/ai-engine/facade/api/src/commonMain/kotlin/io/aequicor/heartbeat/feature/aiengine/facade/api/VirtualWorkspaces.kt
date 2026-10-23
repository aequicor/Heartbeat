package io.aequicor.heartbeat.feature.aiengine.facade.api

/** A versioned text edit; null [content] deletes the file. Paths are relative to the virtual root. */
public data class VirtualFileChange(val path: String, val content: String?)

/**
 * Host-owned files over an immutable Git base. Implementations persist changes before returning and reject stale
 * revisions, paused workspaces, protected paths and paths outside the root. No method materializes a checkout.
 */
public interface VirtualWorkspace {
    /** Opaque workspace identity, independent of native session lifetime. */
    public val ref: WorkspaceRef

    /** Empty host-owned process directory; it contains neither project sources nor executable project config. */
    public val processDirectory: String

    /** Rechecks source availability and returns the current durable content revision. */
    public suspend fun revision(): Long

    /** All regular project file paths, including virtual additions and excluding deletions. */
    public suspend fun paths(): List<String>

    /** Exact UTF-8 text, or null for a missing file. Binary files and links are rejected explicitly. */
    public suspend fun read(path: String): String?

    /** Search skips binary files; other availability/path failures still propagate. */
    public suspend fun readForSearch(path: String): String? = read(path)

    /** One immutable search operation; false from [visit] stops scanning. Binary files and links are skipped. */
    public suspend fun scanTextFiles(paths: List<String>, visit: (String, String) -> Boolean) {
        for (path in paths) {
            val text = readForSearch(path)
            if (text != null && !visit(path, text)) break
        }
    }

    /** Atomically applies all changes to [expectedRevision], returning the persisted new revision. */
    public suspend fun change(expectedRevision: Long, changes: List<VirtualFileChange>): Long

    /** Moves the Git entry atomically, preserving binary content, links and executable mode. */
    public suspend fun move(expectedRevision: Long, path: String, destination: String): Long
}

/** Lazy profile contribution; unknown refs return null, storage failures must propagate. */
public fun interface VirtualWorkspaceProvider {
    /** Resolves only workspaces owned by this provider. */
    public suspend fun find(ref: WorkspaceRef): VirtualWorkspace?
}
