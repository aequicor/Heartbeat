package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Local-folder selection and registration; only stable project ids leave this service. */
interface StudioProjects {
    /** Whether this platform and the current feature switches allow selection. */
    val availability: Flow<Boolean>

    /** Isolated execution is offered only by Desktop engine-backed projects. */
    val worktreeAvailability: Flow<Boolean> get() = flowOf(false)

    /** Returns a registered opaque project id, or null when the user cancels. */
    suspend fun choose(): String?
}

/** Platform folder chooser. Native paths are passed only to the local workspace registry. */
interface StudioDirectoryPicker {
    /** False on platforms without access to local CLI working directories. */
    val isAvailable: Boolean

    /** Returns the selected directory, or null when cancelled; does not read its contents. */
    suspend fun pick(): String?
}
