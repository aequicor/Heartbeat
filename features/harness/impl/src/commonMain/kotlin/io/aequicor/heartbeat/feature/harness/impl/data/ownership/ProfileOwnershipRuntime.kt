package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipCoordinatorClient
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipExecutionGate
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipRead
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.HarnessOwnershipRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.NativeOwnershipAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipNativeIntent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock

/** Real coordinator and protected proposals; existing bindings are enforced independently of feature switches. */
internal class ProfileOwnershipRuntime(
    val journal: ProtectedOwnershipJournal,
    transport: GitHubTransport,
    clock: Clock,
) : HarnessOwnershipRuntime {
    internal val api = GitHubApi(transport, clock)
    private val log = Log.tag("ProfileOwnership")

    fun backend(identity: GitHubTaskIdentity): GitHubOwnershipBackend = GitHubOwnershipBackend(api, identity.repository)

    suspend fun coordinator(identity: GitHubTaskIdentity): OwnershipCoordinatorClient =
        OwnershipCoordinatorClient(backend(identity), journal.host())

    /** Verifies stable server identity independently of the caller's repository/task labels. */
    suspend fun task(repository: GitHubRepository, kind: GitHubTaskKind, number: Long): GitHubTaskIdentity? {
        if (number <= 0) return null
        val root = "repos/${repository.owner}/${repository.name}"
        val repo = api.call(GitHubRequest("GET", root))
        val namespace = when (kind) {
            GitHubTaskKind.Issue -> "issues"
            GitHubTaskKind.Pull -> "pulls"
        }
        val task = api.call(
            GitHubRequest("GET", "$root/$namespace/$number"),
        )
        val metadata = repo.body as? JsonObject
        val detail = task.body as? JsonObject
        fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.content
        val repositoryNode = metadata.text("node_id")
        val taskNode = detail.text("node_id")
        val isIssue = kind == GitHubTaskKind.Issue
        val isMatching = detail.text(
            "number",
        )?.toLongOrNull() == number && (!isIssue || detail?.get("pull_request") == null)
        val isSuccess = repo.status == HTTP_OK && task.status == HTTP_OK && isMatching
        return if (isSuccess && repositoryNode != null && taskNode != null) {
            GitHubTaskIdentity(
                repository,
                kind,
                number,
                GitHubNodeId(repositoryNode),
                GitHubNodeId(taskNode),
            )
        } else {
            null
        }
    }

    override suspend fun binding(owner: HarnessId, record: ProcessRecord): OwnershipLocalBinding? =
        journal.binding(record.local(owner)).also {
            check(record.ownershipKey == null || it?.fence?.key == record.ownershipKey) {
                "Protected ownership binding is unavailable"
            }
        }

    override suspend fun hasBindings(owner: HarnessId): Boolean = journal.hasBindings(owner)
    override suspend fun isBound(local: OwnershipLocalProcess): Boolean = journal.binding(local) != null

    override suspend fun enter(
        owner: HarnessId,
        record: ProcessRecord,
        kind: OwnershipPermitKind,
        correlation: String,
    ): NativeOwnershipAdmission {
        log.v { "admit process effect" }
        val binding = binding(owner, record) ?: return NativeOwnershipAdmission.Legacy
        return if (record.ownershipKey != binding.fence.key) {
            NativeOwnershipAdmission.Unavailable
        } else {
            admit(binding, kind, correlation)
        }
    }

    private suspend fun admit(
        binding: OwnershipLocalBinding,
        kind: OwnershipPermitKind,
        correlation: String,
    ): NativeOwnershipAdmission {
        val id = correlationId(kind, correlation)
        val gate = OwnershipExecutionGate(coordinator(binding.identity), binding.fence)
        val previous = journal.intent(binding.local, id)
        val proposal = previous?.permit ?: gate.propose(id, kind) ?: return NativeOwnershipAdmission.Unavailable
        val intent = OwnershipNativeIntent(binding.local, proposal)
        return if (!journal.reserve(intent)) {
            NativeOwnershipAdmission.Unavailable
        } else {
            when (gate.admit(proposal)) {
                is OwnershipAdmission.Fresh -> NativeOwnershipAdmission.Fresh(intent)
                is OwnershipAdmission.Recover -> NativeOwnershipAdmission.Recover(intent)
                OwnershipAdmission.Unavailable -> NativeOwnershipAdmission.Unavailable
            }
        }
    }

    override suspend fun find(
        owner: HarnessId,
        record: ProcessRecord,
        kind: OwnershipPermitKind,
        correlation: String,
    ): OwnershipNativeIntent? = journal.intent(record.local(owner), correlationId(kind, correlation))

    override suspend fun complete(intent: OwnershipNativeIntent): Boolean {
        log.v { "confirm process effect completion" }
        val binding = journal.binding(intent.local) ?: return false
        check(journal.intent(intent.local, intent.permit.id) == intent) { "Unregistered ownership receipt" }
        return OwnershipExecutionGate(coordinator(binding.identity), intent.permit.fence).complete(intent.permit)
    }

    override suspend fun isCurrent(intent: OwnershipNativeIntent): Boolean {
        val binding = journal.binding(intent.local) ?: return false
        val record = (coordinator(binding.identity).read(binding.fence.key) as? OwnershipRead.Current)?.record
        return record?.accepts(intent.permit.fence) == true && intent.permit in record.permits
    }
}

internal fun ProcessRecord.local(owner: HarnessId): OwnershipLocalProcess = OwnershipLocalProcess(
    owner,
    id,
    incarnation,
)
private const val CORRELATION_CHARS = 32

private fun correlationId(kind: OwnershipPermitKind, correlation: String): String =
    "${kind.name}\n$correlation".encodeUtf8().sha256().hex().take(CORRELATION_CHARS)

private const val HTTP_OK = 200
