package io.aequicor.heartbeat.feature.harness.impl.data.decision

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessActionBinding
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessGitHubSnapshot
import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.SubjectSha
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionHost
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubAuthorization
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHostAuthority
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.authority
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The same native decision host as fixed commands, with a distinct typed payload and digest domain. */
@SingleIn(ProfileScope::class)
@Inject
internal class GitHubProcessAuthorityFactory(
    private val host: ProcessDecisionHost,
    private val commands: LocalCommands,
    private val scopes: HarnessCreationScope,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) {
    fun create(
        request: HarnessActivationRequest,
        access: HarnessInstanceAccess,
        processes: ScriptProcesses,
    ): GitHubHostAuthority = Authority(request, access, processes)

    private inner class Authority(
        private val request: HarnessActivationRequest,
        private val access: HarnessInstanceAccess,
        private val processes: ScriptProcesses,
    ) : GitHubHostAuthority {
        private val log = Log.tag("GitHubProcessAuthority")

        override suspend fun allows(operation: GitHubOperation): Boolean = safe(false) {
            val binding = binding(operation) ?: return@safe false
            val entry = host.ready()?.find(binding) ?: return@safe false
            entry.github == operation.snapshot() && entry.decision.permits(binding, clock.now())
        }

        override suspend fun prepare(operation: GitHubOperation): GitHubAuthorization = safe<GitHubAuthorization>(
            GitHubAuthorization.Stopped(GitHubWriteResult.Refused(GitHubWriteRefusal.NotAuthorized)),
        ) {
            val binding = binding(operation) ?: return@safe stopped()
            val ledger = host.ready() ?: return@safe stopped()
            val entry = ledger.find(binding) ?: request(operation, binding) ?: return@safe stopped()
            if (entry.github != operation.snapshot() || entry.command != null) return@safe stopped()
            when {
                entry.decision.permits(binding, clock.now()) -> GitHubAuthorization.Allowed(entry.decision.id, binding)

                entry.decision.phase == ProcessDecisionPhase.Executing ||
                    entry.decision.phase == ProcessDecisionPhase.Completed ->
                    GitHubAuthorization.Stopped(GitHubWriteResult.Unknown)

                else -> GitHubAuthorization.Stopped(
                    GitHubWriteResult.Decision(entry.decision.id, entry.decision.phase),
                )
            }
        }

        /** Called only under both the process writer barrier and the profile API queue, immediately before send. */
        override suspend fun claim(operation: GitHubOperation): Boolean = safe(false) {
            val id = operation.decisionId ?: return@safe false
            val binding = binding(operation) ?: return@safe false
            val entry = host.ready()?.find(id) ?: return@safe false
            val isSamePayload = entry.github == operation.snapshot() && entry.command == null &&
                operation.decisionBinding == binding
            if (!isSamePayload || !entry.decision.permits(binding, clock.now())) {
                return@safe false
            }
            if (!host.edit(ProcessDecisionEdit.Change(id, ProcessDecisionCommand.Claim(binding)))) return@safe false
            val current = host.ready()?.find(id)?.decision ?: return@safe false
            access.isActive && toggles.get(HarnessProcessCommandsEnabled) && current.binding == binding &&
                current.phase == ProcessDecisionPhase.Executing && clock.now() < current.expiresAt
        }

        /** Uses the original identity, even if reconciliation happens after stage/input changes. */
        override suspend fun complete(operation: GitHubOperation) {
            safe(false) {
                val entry = operation.decisionId?.let { host.ready()?.find(it) } ?: return@safe false
                // Older reservations have no binding copy; the private host entry still owns their origin identity.
                val original = operation.decisionBinding ?: entry.decision.binding
                if (original != bindingSnapshot(operation, original.harnessRevision)) return@safe false
                if (entry.github != operation.snapshot() || entry.decision.binding != original ||
                    entry.decision.phase != ProcessDecisionPhase.Executing
                ) {
                    return@safe false
                }
                host.edit(ProcessDecisionEdit.Change(entry.decision.id, ProcessDecisionCommand.Complete))
            }
        }

        private suspend fun request(
            operation: GitHubOperation,
            binding: ProcessActionBinding,
        ): ProcessCommandDecision? {
            val now = clock.now()
            val entry = ProcessCommandDecision(
                ProcessDecision("pd_" + Uuid.random().toHexString(), binding, now, now + 24.hours),
                github = operation.snapshot(),
            )
            return if (host.edit(ProcessDecisionEdit.Request(entry))) host.ready()?.find(binding) else null
        }

        private suspend fun binding(operation: GitHubOperation): ProcessActionBinding? {
            if (!access.isActive || !commands.isAvailable || !toggles.get(HarnessProcessCommandsEnabled)) return null
            val head = operation.change.expectedHead() ?: return null
            if (!isCurrent(operation) || !isWithinScope(operation)) return null
            val localHead = readGit(operation, listOf("rev-parse", "--verify", "HEAD"))
            val origin = readGit(operation, listOf("config", "--get", "remote.origin.url"))?.githubRepository()
            val repository = "github.com/${operation.repository.owner}/${operation.repository.name}".lowercase()
            if (localHead != head.value || origin != repository || !access.isActive) return null
            return bindingSnapshot(operation, request.harness.revision)
        }

        /** Pure reconstruction uses the saved origin revision for cleanup after a later source activation. */
        private fun bindingSnapshot(operation: GitHubOperation, harnessRevision: Long): ProcessActionBinding? {
            val context = operation.context ?: return null
            val head = operation.change.expectedHead() ?: return null
            val repository = "github.com/${operation.repository.owner}/${operation.repository.name}".lowercase()
            val digest = ("github-action-v1\n" + Json.encodeToString(operation.snapshot())).encodeUtf8().sha256().hex()
            return ProcessActionBinding(
                request.harness.id,
                request.item.id,
                harnessRevision,
                context.attempt,
                operation.inputRevision,
                head,
                context.workspace,
                repository,
                operation.change.authority(),
                digest,
            ).takeIf {
                it.process.process == operation.process && it.process.incarnation == operation.incarnation
            }
        }

        private suspend fun isCurrent(operation: GitHubOperation): Boolean {
            val context = operation.context ?: return false
            val record = processes.get(operation.process) ?: return false
            val isSameInput =
                record.incarnation == operation.incarnation && record.inputRevision == operation.inputRevision
            val isSameSubject = record.subject == operation.change.expectedHead() && record.phase !is ProcessPhase.Ended
            val isSameAttempt = record.workflowKey(context.attempt.stage) == context.attempt &&
                context.attempt.process == operation.process
            return isSameInput && isSameSubject && isSameAttempt
        }

        private suspend fun isWithinScope(operation: GitHubOperation): Boolean {
            val context = operation.context ?: return false
            val projects = (scopes.resolve(context.workspace) as? HarnessScope.Projects)?.projects ?: return false
            val allowed = (request.harness.scope as? HarnessScope.Projects)?.projects
            return allowed == null || allowed.containsAll(projects)
        }

        private suspend fun readGit(operation: GitHubOperation, arguments: List<String>): String? {
            val workspace = checkNotNull(operation.context).workspace
            val outcome = commands.run(LocalCommandRequest(workspace, "git", arguments, 10.seconds))
            return (outcome as? LocalCommandOutcome.Exited)?.takeIf { it.exitCode == 0 }?.output?.trim()
        }

        private suspend fun <T> safe(fallback: T, block: suspend () -> T): T = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val safe = IllegalStateException("GitHub process decision is unavailable")
            log.w(safe) { "GitHub process admission failed (${error::class.simpleName.orEmpty()})" }
            fallback
        }
    }
}

private fun stopped(): GitHubAuthorization =
    GitHubAuthorization.Stopped(GitHubWriteResult.Refused(GitHubWriteRefusal.NotAuthorized))

private fun GitHubOperation.snapshot(): ProcessGitHubSnapshot = ProcessGitHubSnapshot(repository, change, key)

private fun GitHubChange.expectedHead(): SubjectSha? = when (this) {
    is GitHubChange.Merge -> head

    is GitHubChange.CreateRelease -> head

    is GitHubChange.Dispatch -> head

    is GitHubChange.CreateIssue, is GitHubChange.CreatePull, is GitHubChange.Comment,
    is GitHubChange.AddSubIssue, is GitHubChange.AddProjectItem,
    -> null
}

/** Canonical github.com origin; no credentials or arbitrary host names are accepted. */
private fun String.githubRepository(): String? {
    val https = Regex("""https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?""")
    val ssh = Regex("""git@github\.com:([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?""")
    return (
        https.matchEntire(
            this,
        ) ?: ssh.matchEntire(this)
    )?.groupValues?.get(1)?.let { "github.com/${it.lowercase()}" }
}
