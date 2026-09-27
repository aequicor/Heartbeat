package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlinx.coroutines.flow.Flow

/** Optional entry points offered by the studio. */
interface StudioEntries {
    /** Whether the engine connection settings can be opened. */
    val showsConnections: Flow<Boolean>
}
