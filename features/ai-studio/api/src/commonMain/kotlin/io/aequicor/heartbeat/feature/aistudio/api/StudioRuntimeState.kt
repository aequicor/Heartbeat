package io.aequicor.heartbeat.feature.aistudio.api

import kotlin.time.Instant

/** A live permission offered by the engine; only these exact options can be answered. */
public data class StudioPermission(
    val sessionId: String,
    val requestId: String,
    val title: String,
    val options: List<StudioPermissionOption>,
    /** Optional explanation under [title]. */
    val description: String? = null,
    /** Structured answer the engine expects besides the chosen option; null for plain buttons. */
    val input: StudioPermissionInput? = null,
)

/** Structured input of a permission; its options stay the submit (first) and skip (last) actions. */
public sealed interface StudioPermissionInput {
    /** Exactly one of [choices]. */
    public data class SingleChoice(val choices: List<StudioPermissionOption>) : StudioPermissionInput

    /** Between [min] and [max] of [choices]. */
    public data class MultiChoice(val choices: List<StudioPermissionOption>, val min: Int, val max: Int) :
        StudioPermissionInput

    /** Free text. */
    public data class FreeText(val placeholder: String?, val isMultiline: Boolean) : StudioPermissionInput
}

/** Structured answer sent together with an option. */
public sealed interface StudioPermissionAnswer {
    /** Chosen choice ids. */
    public data class Selected(val ids: List<String>) : StudioPermissionAnswer

    /** Entered text. */
    public data class Text(val value: String) : StudioPermissionAnswer
}

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
