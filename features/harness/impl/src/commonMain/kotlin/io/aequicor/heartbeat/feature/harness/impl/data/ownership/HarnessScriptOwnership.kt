package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.api.process.ProcessPhase
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ScriptOwnershipResult
import io.aequicor.heartbeat.feature.harness.api.process.ScriptProcessOwnership
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.transfer.GitHubOwnershipTransferArtifacts
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.transfer.Sha256OwnershipTransferDigests
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipChange
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipCoordinatorClient
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipRead
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferHost
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer.OwnershipTransferResult
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8

/** Activation-owned requests only. All consent, task identity, receipts and imported bytes belong to the host. */
internal class HarnessScriptOwnership(
    private val target: HarnessInstanceTarget,
    private val host: OwnershipRuntimeHost,
    private val local: RuntimeOwnershipTransferLocal,
    private val toggles: FeatureToggles,
    private val origins: HarnessCallOrigins,
) : ScriptProcessOwnership {
    private val request get() = target.request
    private val access get() = target.access
    private val data get() = host.data
    private val runtime get() = host.runtime

    private val log = Log.tag("ScriptOwnership")

    override suspend fun host(): ExecutorHostId? {
        check(access.isActive && !origins.current().isHookRestricted)
        return if (toggles.get(HarnessProcessOwnershipEnabled)) runtime.journal.host() else null
    }

    override suspend fun enroll(
        process: ProcessId,
        repository: GitHubRepository,
        kind: GitHubTaskKind,
        number: Long,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult = activated {
        if (!toggles.get(HarnessProcessOwnershipEnabled)) return@activated ScriptOwnershipResult.Unavailable
        val identity = runtime.task(repository, kind, number) ?: return@activated ScriptOwnershipResult.Unavailable
        val record = local.records().singleOrNull { it.id == process } ?: return@activated ScriptOwnershipResult.Refused
        val binding = binding(identity, record.id, record.incarnation, workspace)
        recoverEnrollment(binding, record) ?: enrollFresh(binding, record, workspace)
    }

    private suspend fun recoverEnrollment(
        binding: OwnershipTransferBinding,
        record: ProcessRecord,
    ): ScriptOwnershipResult? {
        val existing = runtime.journal.binding(binding.local()) ?: return null
        val observed = (runtime.coordinator(binding.identity).read(binding.key) as? OwnershipRead.Current)?.record
        return when {
            existing.fence.key != binding.key -> ScriptOwnershipResult.Refused

            observed?.accepts(existing.fence) == true && existing.definitionDigest == binding.definitionDigest ->
                publishEnrollment(binding, record, existing, observed).also {
                    if (it is ScriptOwnershipResult.Current) consent().recover(observed)
                }

            observed != null && observed.fence.host != runtime.journal.host() -> {
                discardForeignEnrollment(observed)
                ScriptOwnershipResult.Refused
            }

            else -> null
        }
    }

    private suspend fun enrollFresh(
        binding: OwnershipTransferBinding,
        record: ProcessRecord,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult {
        if (!record.isFreshForEnrollment()) return ScriptOwnershipResult.Refused
        val consent = consent()
        return if (!consent.enroll(binding, record)) {
            consent.pending()
        } else {
            registerEnrollment(binding, record, workspace, consent)
        }
    }

    private suspend fun registerEnrollment(
        binding: OwnershipTransferBinding,
        record: ProcessRecord,
        workspace: WorkspaceRef,
        consent: NativeOwnershipConsent,
    ): ScriptOwnershipResult {
        val backend = runtime.backend(binding.identity)
        if (!backend.prepare()) return ScriptOwnershipResult.Unavailable
        val coordinator = runtime.coordinator(binding.identity)
        val read = coordinator.read(binding.key)
        return if (read !is OwnershipRead.Current || read.record != null) {
            ScriptOwnershipResult.Refused
        } else {
            data.processMutex.withLock {
                registerEnrollmentLocked(binding, record, workspace, consent, coordinator)
            }
        }
    }

    private suspend fun registerEnrollmentLocked(
        binding: OwnershipTransferBinding,
        record: ProcessRecord,
        workspace: WorkspaceRef,
        consent: NativeOwnershipConsent,
        coordinator: OwnershipCoordinatorClient,
    ): ScriptOwnershipResult {
        val isUnchanged = local.records().singleOrNull { it.id == record.id } == record && local.canEnroll(record)
        val owned = OwnershipLocalBinding(
            binding.local(),
            binding.identity,
            OwnershipFence(binding.key, runtime.journal.host(), 1),
            workspace,
            binding.definitionDigest,
        )
        return if (!isUnchanged || !runtime.journal.beginEnrollment(owned)) {
            ScriptOwnershipResult.Refused
        } else {
            when (val registered = coordinator.create(binding.key)) {
                is OwnershipChange.Current -> publishEnrollmentLocked(binding, record, owned, registered.record).also {
                    if (it is ScriptOwnershipResult.Current) consent.completed()
                }

                OwnershipChange.Conflict -> {
                    val observed = (coordinator.read(binding.key) as? OwnershipRead.Current)?.record
                    if (observed != null) discardForeignEnrollmentLocked(observed)
                    ScriptOwnershipResult.Unavailable
                }

                else -> ScriptOwnershipResult.Unavailable
            }
        }
    }

    private suspend fun publishEnrollment(
        binding: OwnershipTransferBinding,
        record: ProcessRecord,
        owned: OwnershipLocalBinding,
        observed: ProcessOwnership,
    ): ScriptOwnershipResult = data.processMutex.withLock {
        publishEnrollmentLocked(binding, record, owned, observed)
    }

    private suspend fun publishEnrollmentLocked(
        binding: OwnershipTransferBinding,
        record: ProcessRecord,
        owned: OwnershipLocalBinding,
        observed: ProcessOwnership,
    ): ScriptOwnershipResult {
        val isUnchanged = local.records().singleOrNull { it.id == record.id } == record
        val isEligible = record.ownershipKey == binding.key || record.isFreshForEnrollment()
        val isPending = runtime.journal.pendingEnrollment(binding.key) == owned
        val isNativeStopped = !isPending || local.canEnroll(record)
        val next = if (record.ownershipKey == binding.key) {
            record
        } else {
            record.copy(definition = binding.definition, ownershipKey = binding.key)
        }
        val isPermitted = isUnchanged && isEligible && isNativeStopped
        val isStored = isPermitted && local.replace(record, next)
        return if (isStored && runtime.journal.promoteEnrollment(owned)) {
            ScriptOwnershipResult.Current(observed)
        } else {
            ScriptOwnershipResult.Unavailable
        }
    }

    private suspend fun discardForeignEnrollment(observed: ProcessOwnership): Boolean =
        data.processMutex.withLock { discardForeignEnrollmentLocked(observed) }

    private suspend fun discardForeignEnrollmentLocked(observed: ProcessOwnership): Boolean {
        val pending = runtime.journal.pendingEnrollment(observed.fence.key)
        return if (pending == null) {
            true
        } else {
            val records = HarnessScriptProcesses.decodeStored(
                data.read(
                    pending.local.harness,
                    HarnessScriptProcesses.STORAGE_KEY,
                ) {
                    check(access.isActive)
                }.value,
            )
            val isFresh = records.singleOrNull { it.id == pending.local.process }
                ?.takeIf { it.incarnation == pending.local.incarnation }?.isFreshForEnrollment() == true
            isFresh && runtime.journal.discardEnrollment(pending, observed)
        }
    }

    override suspend fun prepare(
        process: ProcessId,
        target: ExecutorHostId,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult = activated {
        if (!toggles.get(HarnessProcessOwnershipEnabled)) return@activated ScriptOwnershipResult.Unavailable
        operate(process, workspace) { host, binding -> host.begin(binding, target) }
    }

    override suspend fun claim(
        repository: GitHubRepository,
        kind: GitHubTaskKind,
        number: Long,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult = activated {
        if (!toggles.get(HarnessProcessOwnershipEnabled)) return@activated ScriptOwnershipResult.Unavailable
        val identity = runtime.task(repository, kind, number) ?: return@activated ScriptOwnershipResult.Unavailable
        val key = io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey(
            identity.canonical.encodeUtf8().sha256().hex(),
        )
        val current = (runtime.coordinator(identity).read(key) as? OwnershipRead.Current)?.record
            ?: return@activated ScriptOwnershipResult.Unavailable
        claimCurrent(identity, current, workspace)
    }

    private suspend fun claimCurrent(
        identity: GitHubTaskIdentity,
        current: ProcessOwnership,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult = when {
        current.transfer?.target != runtime.journal.host() -> ScriptOwnershipResult.Refused
        !discardForeignEnrollment(current) -> ScriptOwnershipResult.Refused
        else -> claimArtifact(identity, current, workspace)
    }

    private suspend fun claimArtifact(
        identity: GitHubTaskIdentity,
        current: ProcessOwnership,
        workspace: WorkspaceRef,
    ): ScriptOwnershipResult {
        val artifacts = GitHubOwnershipTransferArtifacts(runtime.backend(identity))
        val checkpoint = current.transfer?.checkpoint?.let { artifacts.load(it) }
            ?: return ScriptOwnershipResult.Unavailable
        val binding = binding(identity, checkpoint.process, checkpoint.incarnation, workspace)
        val consent = consent()
        val host = host(binding, consent)
        val result = if (current.phase == OwnershipPhase.Reconciling && current.fence.host == runtime.journal.host()) {
            host.advance(binding)
        } else {
            host.claim(binding)
        }
        return finish(result, consent)
    }

    override suspend fun advance(process: ProcessId, workspace: WorkspaceRef): ScriptOwnershipResult = activated {
        operate(process, workspace) { host, binding -> host.advance(binding) }
    }

    override suspend fun cancel(process: ProcessId, workspace: WorkspaceRef): ScriptOwnershipResult = activated {
        operate(process, workspace) { host, binding -> host.cancel(binding) }
    }

    override suspend fun status(process: ProcessId): ScriptOwnershipResult = activated {
        val stored = runtime.journal.bindings(request.harness.id).singleOrNull { it.local.process == process }
            ?: return@activated ScriptOwnershipResult.Unavailable
        val current = (runtime.coordinator(stored.identity).read(stored.fence.key) as? OwnershipRead.Current)?.record
        if (current == null) {
            ScriptOwnershipResult.Unavailable
        } else {
            consent().recover(current)
            ScriptOwnershipResult.Current(current)
        }
    }

    private suspend fun operate(
        process: ProcessId,
        workspace: WorkspaceRef,
        operation: suspend (OwnershipTransferHost, OwnershipTransferBinding) -> OwnershipTransferResult,
    ): ScriptOwnershipResult {
        val stored = runtime.journal.bindings(request.harness.id).singleOrNull { it.local.process == process }
            ?: return ScriptOwnershipResult.Unavailable
        val binding = binding(stored.identity, process, stored.local.incarnation, workspace)
        val consent = consent()
        return finish(operation(host(binding, consent), binding), consent)
    }

    private suspend fun host(
        binding: OwnershipTransferBinding,
        consent: NativeOwnershipConsent,
    ): OwnershipTransferHost = OwnershipTransferHost(
        runtime.journal.host(),
        runtime.coordinator(binding.identity),
        consent,
        local,
        GitHubOwnershipTransferArtifacts(runtime.backend(binding.identity)),
        Sha256OwnershipTransferDigests(),
    )

    private fun binding(
        identity: GitHubTaskIdentity,
        process: ProcessId,
        incarnation: String,
        workspace: WorkspaceRef,
    ) = OwnershipTransferBinding(
        identity,
        ProcessDefinition(request.item.id, request.harness.revision),
        ownershipDefinitionDigest(request),
        io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess(
            request.harness.id,
            process,
            incarnation,
        ),
        workspace.value,
        Sha256OwnershipTransferDigests().key(identity),
    )

    private fun consent() = NativeOwnershipConsent(target, host, local::records)

    private suspend fun finish(
        result: OwnershipTransferResult,
        consent: NativeOwnershipConsent,
    ): ScriptOwnershipResult = when (result) {
        is OwnershipTransferResult.Coordinated -> when (val change = result.change) {
            is OwnershipChange.Current -> {
                consent.completed()
                consent.recover(change.record)
                ScriptOwnershipResult.Current(change.record)
            }

            else -> ScriptOwnershipResult.Unavailable
        }

        is OwnershipTransferResult.Waiting -> waitingResult(result.snapshot, consent)

        is OwnershipTransferResult.Refused -> consent.decisionId?.let { ScriptOwnershipResult.Decision(it) }
            ?: ScriptOwnershipResult.Refused

        is OwnershipTransferResult.Unavailable -> ScriptOwnershipResult.Unavailable
    }

    private suspend fun waitingResult(
        snapshot: ProcessOwnership,
        consent: NativeOwnershipConsent,
    ): ScriptOwnershipResult {
        consent.recover(snapshot)
        return if (snapshot.phase in setOf(OwnershipPhase.Active, OwnershipPhase.Exported)) {
            ScriptOwnershipResult.Current(snapshot)
        } else {
            ScriptOwnershipResult.Pending
        }
    }

    private suspend fun activated(block: suspend () -> ScriptOwnershipResult): ScriptOwnershipResult {
        check(access.isActive) { "Ownership requires a published activation" }
        check(!origins.current().isHookRestricted) { "Hooks cannot transfer process ownership" }
        val caller = currentCoroutineContext().job
        val cancellation = access.scope.coroutineContext.job.invokeOnCompletion { caller.cancel() }
        return try {
            log.v { "process ownership request" }
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(IllegalStateException("Ownership request is unavailable")) {
                "Ownership request failed (${error::class.simpleName.orEmpty()})"
            }
            ScriptOwnershipResult.Unavailable
        } finally {
            cancellation.dispose()
        }
    }
}

private fun ProcessRecord.isFreshForEnrollment(): Boolean = phase == ProcessPhase.Idle && history.isEmpty() &&
    nextAttempt == 1 && nextWait == 1 && writes.isEmpty() && ownershipKey == null
private fun NativeOwnershipConsent.pending(): ScriptOwnershipResult = decisionId?.let {
    ScriptOwnershipResult.Decision(
        it,
    )
}
    ?: ScriptOwnershipResult.Unavailable
