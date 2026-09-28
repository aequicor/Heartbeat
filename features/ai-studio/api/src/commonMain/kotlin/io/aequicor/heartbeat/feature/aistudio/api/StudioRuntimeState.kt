package io.aequicor.heartbeat.feature.aistudio.api

/** A live permission offered by the engine; only these exact options can be answered. */
public data class StudioPermission(
    val sessionId: String,
    val requestId: String,
    val title: String,
    val options: List<StudioPermissionOption>,
)

/** Opaque decision id and user-visible title supplied by the engine. */
public data class StudioPermissionOption(val id: String, val title: String)

/** Profile-owned executions observed by the feature without owning their lifetime. */
public data class StudioRuntimeState(
    val running: Set<String> = emptySet(),
    val permissions: List<StudioPermission> = emptyList(),
    val stopFailures: Set<String> = emptySet(),
    val uncancellable: Set<String> = emptySet(),
)
