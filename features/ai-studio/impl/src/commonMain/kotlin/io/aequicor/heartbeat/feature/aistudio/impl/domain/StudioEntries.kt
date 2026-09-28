package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Optional entry points offered by the studio. */
interface StudioEntries {
    /** Whether projectless Koog research entry points may be offered. */
    val showsResearch: Flow<Boolean> get() = flowOf(false)

    /** Whether the engine connection settings can be opened. */
    val showsConnections: Flow<Boolean>

    /** Whether the profile search settings can be opened. */
    val showsProfileSettings: Flow<Boolean>
}
