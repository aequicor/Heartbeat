package io.aequicor.heartbeat.feature.aistudio.api

import kotlin.time.Instant

/** A live permission offered by the engine; only these exact options can be answered. */
public data class StudioPermission(
    val sessionId: String,
    val requestId: String,
    val title: String,
    val options: List<StudioPermissionOption>,
)

/** Opaque decision id and user-visible title supplied by the engine. */
public data class StudioPermissionOption(val id: String, val title: String)

/**
 * Profile-owned executions observed by the feature without owning their lifetime.
 * [runStartedAt] identifies the current execution's start, independently of native history loading.
 */
public data class StudioRuntimeState(
    val running: Set<String> = emptySet(),
    val permissions: List<StudioPermission> = emptyList(),
    val stopFailures: Set<String> = emptySet(),
    val uncancellable: Set<String> = emptySet(),
    val runStartedAt: Map<String, Instant> = emptyMap(),
)
