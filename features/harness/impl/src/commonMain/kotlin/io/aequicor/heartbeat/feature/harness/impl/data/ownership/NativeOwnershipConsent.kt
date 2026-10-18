package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessActionBinding
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessCommandDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecision
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionCommand
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionEdit
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionPhase
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessOwnershipAction
import io.aequicor.heartbeat.feature.harness.api.decision.ProcessOwnershipSnapshot
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.transfer.Sha256OwnershipTransferDigests
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferAction
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferConsent
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferConsentRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** The existing native decision machine is the only source of consent. No script callback can answer it. */
internal class NativeOwnershipConsent(
    private val target: HarnessInstanceTarget,
    private val host: OwnershipRuntimeHost,
    private val records: suspend () -> List<ProcessRecord>,
) : OwnershipTransferConsent {
    private val request get() = target.request
    private val access get() = target.access
    private val runtime get() = host.runtime
    private val workspace get() = host.workspace
    private val decisions get() = host.decisions
    private val clock get() = host.clock

    var decisionId: String? = null
        private set

    override suspend fun confirm(request: OwnershipTransferConsentRequest): Boolean {
        val context = request.context
        val action = when (request.action) {
            OwnershipTransferAction.Begin -> ProcessOwnershipAction.Prepare
            OwnershipTransferAction.Claim -> ProcessOwnershipAction.Claim
            OwnershipTransferAction.Cancel -> ProcessOwnershipAction.Cancel
        }
        val payload = ProcessOwnershipSnapshot(
            action,
            context.binding.identity,
            context.binding.definitionDigest,
            context.snapshot,
            request.target,
            request.checkpoint?.let { Json.encodeToString(it).encodeUtf8().sha256().hex() },
        )
        val record = records().singleOrNull { it.id == context.binding.process }
        val isApproved = confirm(
            payload,
            context.binding,
            record,
            request.checkpoint?.inputRevision ?: record?.inputRevision ?: 0,
            request.checkpoint?.nextAttempt ?: record?.nextAttempt ?: 1,
        )
        if (isApproved && action == ProcessOwnershipAction.Claim) {
            val local = context.binding.local()
            val previous = runtime.journal.binding(local)
            val fence = context.snapshot.fence.copy(
                host = runtime.journal.host(),
                epoch = context.snapshot.fence.epoch + 1,
            )
            val isReplay = previous?.fence == fence
            val prior = if (isReplay) previous.previousOwner else previous?.fence
            val next = OwnershipLocalBinding(
                local,
                context.binding.identity,
                fence,
                WorkspaceRef(context.binding.workspace),
                context.binding.definitionDigest,
                prior,
                if (isReplay) previous.imported else null,
            )
            return runtime.journal.bind(previous, next)
        }
        return isApproved
    }

    suspend fun enroll(binding: OwnershipTransferBinding, record: ProcessRecord): Boolean = confirm(
        ProcessOwnershipSnapshot(
            ProcessOwnershipAction.Enroll,
            binding.identity,
            binding.definitionDigest,
            null,
            runtime.journal.host(),
        ),
        binding,
        record,
        record.inputRevision,
        record.nextAttempt,
    )

    private suspend fun confirm(
        payload: ProcessOwnershipSnapshot,
        binding: OwnershipTransferBinding,
        record: ProcessRecord?,
        inputRevision: Long,
        nextAttempt: Int,
    ): Boolean {
        check(access.isActive) { "Ownership activation expired" }
        val actionBinding = actionBinding(payload, binding, inputRevision, nextAttempt) ?: return false
        val entry = decision(payload, actionBinding)
        val current = entry?.let { decisions.ready()?.find(it.decision.id)?.decision }
        val isUnchanged = records().singleOrNull { it.id == binding.process } == record
        val freshBinding = actionBinding(payload, binding, inputRevision, nextAttempt)
        val isFresh = access.isActive && isUnchanged && freshBinding == actionBinding
        return isFresh && current != null && admit(current, actionBinding)
    }

    private suspend fun decision(
        payload: ProcessOwnershipSnapshot,
        binding: ProcessActionBinding,
    ): ProcessCommandDecision? {
        val ledger = decisions.ready() ?: return null
        val existing = ledger.find(binding)
        val proposal = ProcessCommandDecision(
            ProcessDecision("pd_" + Uuid.random().toHexString(), binding, clock.now(), clock.now() + 24.hours),
            ownership = payload,
        )
        val entry = existing ?: proposal.takeIf { decisions.edit(ProcessDecisionEdit.Request(it)) }
        decisionId = entry?.decision?.id
        return entry?.takeIf { it.ownership == payload }
    }

    private suspend fun admit(current: ProcessDecision, binding: ProcessActionBinding): Boolean = when {
        // Replay only the same conditional coordinator CAS, never native execution.
        current.phase == ProcessDecisionPhase.Executing -> true

        current.permits(binding, clock.now()) -> decisions.edit(
            ProcessDecisionEdit.Change(current.id, ProcessDecisionCommand.Claim(binding)),
        )

        else -> false
    }

    private suspend fun actionBinding(
        payload: ProcessOwnershipSnapshot,
        binding: OwnershipTransferBinding,
        inputRevision: Long,
        nextAttempt: Int,
    ): ProcessActionBinding? {
        val head = workspace.head(WorkspaceRef(binding.workspace), binding.identity) ?: return null
        return ProcessActionBinding(
            request.harness.id, request.item.id, request.harness.revision,
            ProcessWorkflowKey(binding.process, binding.incarnation, nextAttempt, StageName("ownership")),
            inputRevision, head, WorkspaceRef(binding.workspace),
            "github.com/${binding.identity.repository.owner}/${binding.identity.repository.name}",
            ProcessAuthority.Transfer,
            Json.encodeToString(payload).encodeUtf8().sha256().hex(),
        )
    }

    /** A fresh matching coordinator fact repairs a lost positive CAS response without admitting any effect. */
    suspend fun recover(current: ProcessOwnership) {
        val entries = decisions.ready()?.entries.orEmpty().filter {
            it.decision.binding.harness == request.harness.id && it.decision.phase == ProcessDecisionPhase.Executing
        }
        for (entry in entries) {
            val payload = entry.ownership
            if (payload != null && payload.isApplied(current) && hasLocalEnrollment(entry, current)) {
                decisions.edit(ProcessDecisionEdit.Change(entry.decision.id, ProcessDecisionCommand.Complete))
            }
        }
    }

    private suspend fun hasLocalEnrollment(entry: ProcessCommandDecision, current: ProcessOwnership): Boolean {
        if (entry.ownership?.action != ProcessOwnershipAction.Enroll) return true
        val key = entry.decision.binding.process
        val local = OwnershipLocalProcess(request.harness.id, key.process, key.incarnation)
        val bound = runtime.journal.binding(local)
        val record = records().singleOrNull { it.id == key.process && it.incarnation == key.incarnation }
        return bound?.fence == current.fence && record?.ownershipKey == current.fence.key
    }

    private fun ProcessOwnershipSnapshot.isApplied(current: ProcessOwnership): Boolean {
        if (Sha256OwnershipTransferDigests().key(identity) != current.fence.key) return false
        val before = expected
        val pending = current.transfer
        val isNewer = before == null || current.revision > before.revision
        return isNewer && when (action) {
            ProcessOwnershipAction.Enroll -> current.fence.host == target && current.fence.epoch == 1L

            ProcessOwnershipAction.Prepare ->
                before != null && pending?.source == before.fence &&
                    pending.target == target

            ProcessOwnershipAction.Claim -> rotatedTo(current, target)

            ProcessOwnershipAction.Cancel -> rotatedTo(current, before?.fence?.host)
        }
    }

    private fun ProcessOwnershipSnapshot.rotatedTo(
        current: ProcessOwnership,
        host: io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId?,
    ): Boolean {
        val before = expected ?: return false
        return host != null && current.transfer == before.transfer &&
            current.fence == before.fence.copy(host = host, epoch = before.fence.epoch + 1)
    }

    /** Positive coordinator receipt only; an unknown CAS keeps the native decision Executing. */
    suspend fun completed() {
        decisionId?.let { decisions.edit(ProcessDecisionEdit.Change(it, ProcessDecisionCommand.Complete)) }
    }
}

internal fun OwnershipTransferBinding.local(): OwnershipLocalProcess = OwnershipLocalProcess(
    harness,
    process,
    incarnation,
)
