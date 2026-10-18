package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessActionBinding
import io.aequicor.heartbeat.feature.harness.api.github.GitHubActionContext
import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.uuid.Uuid

/**
 * No eviction: a full registry refuses new keys before any external call.
 * Stored in the protected harness namespace.
 */
internal class GitHubRegistry(private val data: ScriptData) {
    private val log = Log.tag("GitHubRegistry")

    suspend fun find(process: ProcessId, incarnation: String, key: WriteKey): GitHubOperation? = decode(
        data.read(KEY).value,
    ).firstOrNull { it.process == process && it.incarnation == incarnation && it.key == key }

    suspend fun reserve(candidate: GitHubOperation): GitHubReservation {
        var result: GitHubReservation = GitHubReservation.Full
        data.update(KEY) { value ->
            val entries = decode(value)
            val existing = entries.firstOrNull {
                it.process == candidate.process && it.incarnation == candidate.incarnation && it.key == candidate.key
            }
            when {
                existing != null -> {
                    result = GitHubReservation.Existing(existing)
                    value
                }

                entries.size >= MAX_OPERATIONS -> {
                    result = GitHubReservation.Full
                    value
                }

                else -> {
                    result = GitHubReservation.Reserved(candidate)
                    encode(entries + candidate)
                }
            }
        }
        return result
    }

    suspend fun settle(operation: GitHubOperation, result: ResultRef? = null, refusal: GitHubWriteRefusal? = null) {
        data.update(KEY) { value ->
            val entries = decode(value)
            check(entries.any { it.marker == operation.marker }) { "GitHub reservation disappeared" }
            encode(
                entries.map {
                    if (it.marker == operation.marker && it.result == null && it.refusal == null) {
                        it.copy(result = result, refusal = refusal)
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun decode(value: JsonElement?): List<GitHubOperation> {
        if (value == null) return emptyList()
        return try {
            val stored = Json.decodeFromJsonElement<StoredGitHub>(value)
            check(stored.version == 1 && stored.operations.size <= MAX_OPERATIONS)
            check(
                stored.operations.map {
                    Triple(
                        it.process,
                        it.incarnation,
                        it.key,
                    )
                }.distinct().size == stored.operations.size,
            )
            stored.operations
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = IllegalStateException("Stored GitHub registry is unreadable")
            log.w(failure) { "GitHub registry rejected (${error::class.simpleName.orEmpty()})" }
            throw failure
        }
    }

    private fun encode(operations: List<GitHubOperation>): JsonElement =
        Json.encodeToJsonElement(StoredGitHub(1, operations))

    private companion object {
        const val KEY = "hb_github"
        const val MAX_OPERATIONS = 128
    }
}

@Serializable
private data class StoredGitHub(val version: Int, val operations: List<GitHubOperation>) {
    override fun toString(): String = "StoredGitHub(***)"
}

@Serializable
internal data class GitHubOperation(
    val process: ProcessId,
    val incarnation: String,
    val inputRevision: Long,
    val key: WriteKey,
    val repository: GitHubRepository,
    val change: GitHubChange,
    @Required val marker: String = Uuid.random().toHexString(),
    val result: ResultRef? = null,
    val refusal: GitHubWriteRefusal? = null,
    val context: GitHubActionContext? = null,
    val decisionId: String? = null,
    val decisionBinding: ProcessActionBinding? = null,
) {
    fun sameRequest(other: GitHubOperation): Boolean = process == other.process && incarnation == other.incarnation &&
        inputRevision == other.inputRevision && key == other.key && repository == other.repository &&
        change == other.change && context == other.context

    override fun toString(): String = "GitHubOperation(***)"
}

internal sealed interface GitHubReservation {
    data class Reserved(val operation: GitHubOperation) : GitHubReservation
    data class Existing(val operation: GitHubOperation) : GitHubReservation
    data object Full : GitHubReservation
}
