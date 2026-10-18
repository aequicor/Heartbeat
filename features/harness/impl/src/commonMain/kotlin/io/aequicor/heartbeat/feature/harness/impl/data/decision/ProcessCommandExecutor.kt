package io.aequicor.heartbeat.feature.harness.impl.data.decision

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessActionBinding
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcessCommandResult
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessCommandsEnabled
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.HarnessProcessCommandHost
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessCommandInvocation
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionHost
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.NativeOwnershipAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDataStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.services.result
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** New guarded declarations fail closed while this flag is off; unguarded legacy declarations keep their policy. */
internal val HarnessProcessCommandsEnabled = FeatureToggle.Flag(
    key = "harness.process_actions",
    description = "Харнессы: решения пользователя для точных команд процессов",
    default = false,
)

/**
 * This executor owns the complete admission and dispatch path. It never returns a capability for author code to use
 * later. The process writer barrier fences changes to revision, lifetime and attempt through final admission;
 * external HEAD and repository are freshly read twice. Source review must still require the external command to
 * use its system's conditional operation: arbitrary programs are not made transactional by a local HEAD check.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProcessCommandExecutor(
    private val host: ProcessDecisionHost,
    private val data: HarnessDataStorage,
    private val commands: LocalCommands,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) : HarnessProcessCommandHost {
    private val log = Log.tag("ProcessCommandExecutor")

    override suspend fun execute(invocation: ProcessCommandInvocation): ScriptProcessCommandResult = try {
        data.processMutex.withLock { admitted(invocation) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val safe = IllegalStateException("Process command is unavailable")
        log.w(safe) { "Process command failed (${error::class.simpleName.orEmpty()})" }
        ScriptProcessCommandResult.Unavailable
    }

    private suspend fun admitted(call: ProcessCommandInvocation): ScriptProcessCommandResult {
        if (!isEnabled()) return ScriptProcessCommandResult.Unavailable
        val binding = binding(call) ?: return ScriptProcessCommandResult.Unavailable
        val ledger = host.ready() ?: return ScriptProcessCommandResult.Unavailable
        val entry = ledger.find(binding) ?: request(call, binding) ?: return ScriptProcessCommandResult.Unavailable
        return if (entry.decision.permits(binding, clock.now())) dispatch(call, entry) else entry.status()
    }

    private suspend fun request(
        call: ProcessCommandInvocation,
        binding: ProcessActionBinding,
    ): ProcessCommandDecision? {
        val now = clock.now()
        val entry = ProcessCommandDecision(
            ProcessDecision("pd_" + Uuid.random().toHexString(), binding, now, now + 24.hours),
            call.command,
        )
        return if (host.edit(ProcessDecisionEdit.Request(entry))) host.ready()?.find(binding) else null
    }

    private suspend fun dispatch(
        call: ProcessCommandInvocation,
        entry: ProcessCommandDecision,
    ): ScriptProcessCommandResult {
        if (entry.command != call.command || entry.github != null) return ScriptProcessCommandResult.Unavailable
        val id = entry.decision.id
        if (!claim(call, entry)) return ScriptProcessCommandResult.Unavailable
        check(call.access.isActive) { "Process command activation expired" }
        val current = checkNotNull(record(call)) { "Process disappeared after native admission" }
        check(data.ownership != null || current.ownershipKey == null) { "Ownership runtime is unavailable" }
        val ownership = data.ownership?.enter(
            call.activation.harness.id,
            current,
            OwnershipPermitKind.Command,
            id,
        ) ?: NativeOwnershipAdmission.Legacy
        if (ownership !is NativeOwnershipAdmission.Fresh && ownership != NativeOwnershipAdmission.Legacy) {
            return ScriptProcessCommandResult.Unavailable
        }
        val command = call.command
        val result = commands.run(
            LocalCommandRequest(
                call.workspace,
                command.program,
                command.arguments,
                command.timeoutMillis.milliseconds,
            ),
        ).result()
        // Timeout/cancellation may have partial external effects: preserve the consumed claim for reconciliation.
        if (result !is ScriptCommandResult.TimedOut) {
            // Persist the positive native receipt before releasing the shared permit; restart can repair release.
            check(host.edit(ProcessDecisionEdit.Change(id, ProcessDecisionCommand.Complete)))
            if (ownership is NativeOwnershipAdmission.Fresh) data.ownership?.complete(ownership.intent)
        }
        return ScriptProcessCommandResult.Finished(result)
    }

    private suspend fun claim(call: ProcessCommandInvocation, entry: ProcessCommandDecision): Boolean {
        val finalBinding = binding(call)
        val isEnabled = isEnabled()
        return if (!isEnabled || finalBinding != entry.decision.binding || !call.access.isActive) {
            false
        } else {
            // Inspection precedes irreversible admission. The reducer sees the latest phase and host time.
            val claim = ProcessDecisionEdit.Change(
                entry.decision.id,
                ProcessDecisionCommand.Claim(finalBinding),
            )
            val isClaimed = host.edit(claim)
            val current = if (isClaimed) host.ready()?.find(entry.decision.id)?.decision else null
            current?.phase == ProcessDecisionPhase.Executing && clock.now() < current.expiresAt
        }
    }

    /** Recheck both switches after waiting for the writer barrier and again before irreversible admission. */
    private suspend fun isEnabled(): Boolean =
        toggles.get(HarnessCommandsEnabled) && toggles.get(HarnessProcessCommandsEnabled)

    private suspend fun binding(call: ProcessCommandInvocation): ProcessActionBinding? {
        check(call.access.isActive) { "Process command activation expired" }
        val record = record(
            call,
        )?.takeUnless { call.authority == ProcessAuthority.Transfer }
            ?: return null
        val head = readGit(call, listOf("rev-parse", "--verify", "HEAD"))
        val repository = readGit(call, listOf("config", "--get", "remote.origin.url"))?.repository()
        val subject = record.subject
        val isMatching = subject != null && head == subject.value && repository != null
        val digest = Json.encodeToString(call.command).encodeUtf8().sha256().hex()
        return if (!isMatching) {
            null
        } else {
            ProcessActionBinding(
                call.activation.harness.id,
                call.activation.item.id,
                call.activation.harness.revision,
                call.process,
                record.inputRevision,
                SubjectSha(head),
                call.workspace,
                repository,
                call.authority,
                digest,
            )
        }
    }

    private suspend fun record(call: ProcessCommandInvocation): ProcessRecord? {
        val stored = data.read(call.activation.harness.id, HarnessScriptProcesses.STORAGE_KEY) {
            check(call.access.isActive) { "Process command activation expired" }
        }
        return HarnessScriptProcesses.decodeStored(stored.value).singleOrNull { it.id == call.process.process }
            ?.takeIf { it.phase !is ProcessPhase.Ended && it.workflowKey(call.process.stage) == call.process }
    }

    private suspend fun readGit(call: ProcessCommandInvocation, arguments: List<String>): String? {
        val outcome = commands.run(LocalCommandRequest(call.workspace, "git", arguments, 10.seconds))
        return (outcome as? LocalCommandOutcome.Exited)?.takeIf { it.exitCode == 0 }?.output?.trim()
    }
}

private fun ProcessCommandDecision.status(): ScriptProcessCommandResult =
    ScriptProcessCommandResult.Decision(decision.id, decision.phase)

/** Canonical GitHub repository only; reject credentials and arbitrary display text from git configuration. */
private fun String.repository(): String? {
    val https = Regex("""https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?""")
    val ssh = Regex("""git@github\.com:([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?""")
    val path = (https.matchEntire(this) ?: ssh.matchEntire(this))?.groupValues?.get(1)
    return path?.let { "github.com/${it.lowercase()}" }
}
