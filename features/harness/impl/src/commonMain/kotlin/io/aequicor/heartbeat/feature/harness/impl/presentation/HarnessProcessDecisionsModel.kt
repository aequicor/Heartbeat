package io.aequicor.heartbeat.feature.harness.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionsState
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionHost
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce
import kotlin.time.Clock
import kotlin.time.Instant

/** Host presentation status; the UI never imports action or script contracts. */
internal enum class ProcessDecisionStatusUi { Pending, Approved, Denied, Revoked, Executing, Completed, Expired }

/** All values shown to the human, with a fenced revision and no callback or method supplied by the script. */
@Immutable
internal data class ProcessDecisionUi(
    val id: String,
    val revision: Long,
    val status: ProcessDecisionStatusUi,
    val repository: String,
    val workspace: String,
    val operation: String,
    val subject: String,
    val process: String,
    val stage: String,
    val attempt: Int,
    val inputRevision: Long,
    val expiresAt: String,
    val timeoutMillis: Long,
    val words: String,
) {
    override fun toString(): String = "ProcessDecisionUi(status=$status, ***)"
}

@Immutable
internal data class ProcessDecisionsUiState(
    val rows: ImmutableList<ProcessDecisionUi> = persistentListOf(),
    val isFailed: Boolean = false,
    val isBusy: Boolean = false,
    val isRefused: Boolean = false,
) : MVIState {
    override fun toString(): String =
        "ProcessDecisionsUiState(rows=${rows.size}, isFailed=$isFailed, isBusy=$isBusy, isRefused=$isRefused)"
}

/** Native UI-only intents. No service-as-ui provider, WebView bridge or agent tool exposes this store. */
internal sealed interface ProcessDecisionsUiIntent : MVIIntent {
    data class Answer(val id: String, val revision: Long, val isApproved: Boolean) : ProcessDecisionsUiIntent
    data class Revoke(val id: String, val revision: Long) : ProcessDecisionsUiIntent
    data object Reload : ProcessDecisionsUiIntent
}

/** Mirrors the host machine and sends only native user clicks, bound to the row actually displayed. */
internal class HarnessProcessDecisionsModel(
    harness: String,
    private val host: ProcessDecisionHost,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    clock: Clock,
) {
    val store = factory.create<ProcessDecisionsUiState, ProcessDecisionsUiIntent, MVIAction>(
        "HarnessProcessDecisions",
        ProcessDecisionsUiState(),
        onError = { copy(isFailed = true) },
    ) {
        reflect(host.machine) { reflectDecisions(it, harness, clock.now()) }
        reduce { intent ->
            val command = when (intent) {
                is ProcessDecisionsUiIntent.Answer -> ProcessDecisionEdit.Change(
                    intent.id,
                    ProcessDecisionCommand.Answer(intent.revision, intent.isApproved),
                )

                is ProcessDecisionsUiIntent.Revoke -> ProcessDecisionEdit.Change(
                    intent.id,
                    ProcessDecisionCommand.Revoke(intent.revision),
                )

                ProcessDecisionsUiIntent.Reload -> null
            }
            if (command == null) {
                val isFailed = host.ready() == null
                updateState {
                    reflectDecisions(
                        host.machine.state.value,
                        harness,
                        clock.now(),
                    ).copy(isFailed = isFailed)
                }
            } else {
                var isShown = false
                withState { isShown = rows.any { it.id == command.id && it.revision == command.command.revision() } }
                val isApplied = isShown && host.edit(command)
                updateState {
                    reflectDecisions(host.machine.state.value, harness, clock.now()).copy(
                        isFailed = host.machine.state.value == ProcessDecisionsState.Failed,
                        isRefused = !isApplied && host.machine.state.value != ProcessDecisionsState.Failed,
                    )
                }
            }
        }
    }

    init {
        store.start(scope)
        scope.launch { host.ready() }
    }
}

private fun ProcessDecisionCommand.revision(): Long? = when (this) {
    is ProcessDecisionCommand.Answer -> expectedRevision
    is ProcessDecisionCommand.Revoke -> expectedRevision
    is ProcessDecisionCommand.Claim, ProcessDecisionCommand.Complete -> null
}

private fun ProcessDecisionsUiState.reflectDecisions(
    machine: ProcessDecisionsState,
    harness: String,
    now: Instant,
): ProcessDecisionsUiState = when (machine) {
    ProcessDecisionsState.Idle, ProcessDecisionsState.Loading -> copy(isBusy = true)

    ProcessDecisionsState.Failed -> copy(isFailed = true, isBusy = false)

    is ProcessDecisionsState.Ready -> copy(
        rows = machine.ledger.entries.filter { it.decision.binding.harness.value == harness }
            .map { it.toUi(now) }.toImmutableList(),
        isFailed = false,
        isBusy = machine.pending != null,
        isRefused = false,
    )
}

private fun ProcessCommandDecision.toUi(now: Instant): ProcessDecisionUi {
    val binding = decision.binding
    val command = command
    val words = if (command != null) {
        Json.encodeToString(ListSerializer(String.serializer()), listOf(command.program) + command.arguments)
    } else {
        Json.encodeToString(checkNotNull(github))
    }
    val status = when (decision.phase) {
        ProcessDecisionPhase.Pending -> if (now >= decision.expiresAt) {
            ProcessDecisionStatusUi.Expired
        } else {
            ProcessDecisionStatusUi.Pending
        }

        ProcessDecisionPhase.Approved -> if (now >= decision.expiresAt) {
            ProcessDecisionStatusUi.Expired
        } else {
            ProcessDecisionStatusUi.Approved
        }

        ProcessDecisionPhase.Denied -> ProcessDecisionStatusUi.Denied

        ProcessDecisionPhase.Revoked -> ProcessDecisionStatusUi.Revoked

        ProcessDecisionPhase.Executing -> ProcessDecisionStatusUi.Executing

        ProcessDecisionPhase.Completed -> ProcessDecisionStatusUi.Completed
    }
    return ProcessDecisionUi(
        decision.id,
        decision.revision,
        status,
        binding.repository,
        binding.workspace.value,
        binding.operation.name,
        binding.subject.value,
        binding.process.process.value,
        binding.process.stage.value,
        binding.process.attempt,
        binding.inputRevision,
        decision.expiresAt.toString(),
        command?.timeoutMillis ?: GITHUB_TIMEOUT_MILLIS,
        words,
    )
}

private const val GITHUB_TIMEOUT_MILLIS = 30_000L
