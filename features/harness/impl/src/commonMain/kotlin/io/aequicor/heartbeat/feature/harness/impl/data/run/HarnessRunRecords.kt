package io.aequicor.heartbeat.feature.harness.impl.data.run

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Committed terminal metadata is the only authority for tolerating a missing expired value. */
@Serializable
internal data class HarnessRunHeader(val id: RunId, val harness: HarnessId, val finishedAt: Instant?) {
    val expiresAt: Instant? get() = finishedAt?.plus(RUN_RETENTION)
}

@Serializable
internal data class HarnessRunIndex(val entries: List<HarnessRunHeader> = emptyList(), val version: Int = 1) {
    init {
        require(version == 1 && entries.map { it.id }.distinct().size == entries.size)
    }
}

/** Ephemeral coherent read; private journal content must not reach incidental logs. */
internal data class HarnessRunRecords(
    val indexText: String?,
    val index: HarnessRunIndex,
    val values: Map<RunId, String?>,
    val runs: List<WorkflowRun>,
) {
    override fun toString(): String = "HarnessRunRecords(***)"
}

internal fun WorkflowRun.header(): HarnessRunHeader = HarnessRunHeader(id, harness, finishedAt)

internal fun retainedRuns(runs: List<WorkflowRun>, now: Instant): List<WorkflowRun> {
    val running = runs.filter { it.status == WorkflowStatus.Running }
    val terminal = runs.filter { it.header().expiresAt?.let { expiry -> expiry > now } == true }
        .groupBy { it.harness }.values.flatMap { values ->
            values.sortedWith(compareByDescending<WorkflowRun> { it.finishedAt }.thenBy { it.id.value })
                .take(TERMINAL_RUNS_PER_HARNESS)
        }
    return (running + terminal).sortedBy { it.id.value }
}

internal inline fun <reified T> decodeRunRecord(raw: String): T = try {
    Json.decodeFromString<T>(raw)
} catch (error: IllegalArgumentException) {
    // Serialization errors may include private source, inputs and results. No cause or message escapes.
    val safe = HarnessStorageCorrupt()
    log.w(safe) { "Harness record decode failed (${error::class.simpleName.orEmpty()})" }
    throw safe
}

internal fun WorkflowRun.hasSameIdentity(other: WorkflowRun): Boolean =
    id == other.id && harness == other.harness && workflow == other.workflow && pinned == other.pinned &&
        input == other.input && caller == other.caller && origin == other.origin && startedAt == other.startedAt &&
        deadline == other.deadline && wake == other.wake

internal val RUN_RETENTION = 30.days
private const val TERMINAL_RUNS_PER_HARNESS = 20

private val log = Log.tag("HarnessRunStorage")
