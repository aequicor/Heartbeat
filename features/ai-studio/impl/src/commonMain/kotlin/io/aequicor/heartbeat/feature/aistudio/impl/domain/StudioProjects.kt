package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Local-folder selection and registration; only stable project ids leave this service. */
interface StudioProjects {
    /** Whether this platform and the current feature switches allow selection. */
    val availability: Flow<Boolean>

    /** Isolated execution is offered only by Desktop engine-backed projects. */
    val worktreeAvailability: Flow<Boolean> get() = flowOf(false)

    /** Source-only isolation is offered only when worktrees and the sandbox toggle are enabled. */
    val sandboxAvailability: Flow<Boolean> get() = flowOf(false)

    /** Ids of registered projects whose folder is a Git repository; empty while isolation is unavailable. */
    val repositoryProjects: Flow<Set<String>> get() = flowOf(emptySet())

    /** Returns a registered opaque project id, or null when the user cancels. */
    suspend fun choose(): String?
}

/** Platform folder chooser. Native paths are passed only to the local workspace registry. */
interface StudioDirectoryPicker {
    /** False on platforms without access to local CLI working directories. */
    val isAvailable: Boolean

    /** Returns the selected directory, or null when cancelled; does not read its contents. */
    suspend fun pick(): String?

    /** Whether [directory] lies inside a Git working tree; platforms without local folders answer false. */
    suspend fun isRepository(directory: String): Boolean = false
}
