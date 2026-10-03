package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Optional entry points offered by the studio. */
interface StudioEntries {
    /** Whether new attachment input is available in this profile. */
    val showsAttachments: Flow<Boolean> get() = flowOf(false)

    /** Whether projectless Koog research entry points may be offered. */
    val showsResearch: Flow<Boolean> get() = flowOf(false)

    /** Sessions with open questionnaire questions (inside a profile, while the questionnaire is enabled). */
    val questionSources: Flow<Set<String>> get() = flowOf(emptySet())

    /** Whether the engine connection settings can be opened. */
    val showsConnections: Flow<Boolean>

    /** Whether the profile search settings can be opened. */
    val showsProfileSettings: Flow<Boolean>

    /** Whether the composer offers `/remember` (self-learning is on and a profile is active). */
    val showsRemember: Flow<Boolean> get() = flowOf(false)

    /** Whether one "Settings" entry replaces the separate settings actions. */
    val showsUnifiedSettings: Flow<Boolean> get() = flowOf(false)
}
