package io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermit
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessExport
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessReceipts
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.portableCheckpoint
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.transfer.Sha256OwnershipTransferDigests
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipBackend
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipChange
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipCommit
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipCoordinatorClient
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipRead
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipUnavailable
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class OwnershipTransferHostTest {
    @Test
    fun `each host advances exactly one durable phase and target reconciles before activation`() = runTest {
        val env = TransferEnv()
        env.prepare()
        assertEquals(OwnershipPhase.Preparing, env.backend.current.phase)
        assertEquals(0, env.local.closeCalls)
        env.source.advance(env.binding).changed()
        assertEquals(OwnershipPhase.Quiescing, env.backend.current.phase)
        assertEquals(0, env.local.drainCalls)
        env.source.advance(env.binding).changed()
        assertEquals(OwnershipPhase.Exported, env.backend.current.phase)
        env.target.claim(env.binding).changed()
        assertEquals(OwnershipPhase.Reconciling, env.backend.current.phase)
        assertEquals(2L, env.backend.current.fence.epoch)
        assertEquals(0, env.local.recipientCalls)
        env.target.advance(env.binding).changed()
        assertEquals(OwnershipPhase.Active, env.backend.current.phase)
        assertEquals(env.targetId, env.backend.current.fence.host)
        assertEquals(1, env.local.recipientCalls)
        assertEquals(listOf(OwnershipTransferAction.Begin, OwnershipTransferAction.Claim), env.consent.actions)
        assertEquals(OwnershipPhase.Active, env.consent.requests[0].context.snapshot.phase)
        assertEquals(OwnershipPhase.Exported, env.consent.requests[1].context.snapshot.phase)
        assertEquals(env.manifest, env.consent.requests[1].checkpoint)
        assertEquals(5, env.backend.commits)
    }

    @Test
    fun `declining native consent never closes admission or changes shared state`() = runTest {
        val env = TransferEnv()
        env.consent.allowed = false
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Consent),
            env.source.begin(env.binding, env.targetId),
        )
        assertEquals(0, env.backend.commits)
        assertEquals(0, env.local.closeCalls)
        assertEquals(OwnershipPhase.Active, env.backend.current.phase)
    }

    @Test
    fun `consent applies to its exact snapshot and a concurrent change conflicts`() = runTest {
        val env = TransferEnv()
        env.consent.onConfirm = {
            env.backend.current = env.backend.current.copy(revision = env.backend.current.revision + 1)
        }
        val result = env.source.begin(env.binding, env.targetId)
        assertEquals(OwnershipChange.Conflict, assertIs<OwnershipTransferResult.Coordinated>(result).change)
        assertEquals(OwnershipPhase.Active, env.backend.current.phase)
        assertEquals(0, env.local.closeCalls)
    }

    @Test
    fun `unknown prepare is observed on restart and never submits another prepare`() = runTest {
        val env = TransferEnv()
        env.backend.unknownNext = true
        val result = env.source.begin(env.binding, env.targetId)
        assertEquals(
            OwnershipChange.Unavailable(OwnershipUnavailable.UnknownCommit),
            assertIs<OwnershipTransferResult.Coordinated>(result).change,
        )
        assertEquals(OwnershipPhase.Preparing, env.backend.current.phase)
        val restarted = env.host(env.sourceId)
        restarted.advance(env.binding).changed()
        assertEquals(OwnershipPhase.Quiescing, env.backend.current.phase)
        assertEquals(1, env.consent.actions.size)
        assertEquals(1, env.local.closeCalls)
    }

    @Test
    fun `unknown exported CAS never repeats drainage or artifact publication`() = runTest {
        val env = TransferEnv()
        env.quiesce()
        env.backend.unknownNext = true
        val result = env.source.advance(env.binding)
        assertEquals(
            OwnershipChange.Unavailable(OwnershipUnavailable.UnknownCommit),
            assertIs<OwnershipTransferResult.Coordinated>(result).change,
        )
        assertEquals(OwnershipPhase.Exported, env.backend.current.phase)
        assertIs<OwnershipTransferResult.Waiting>(env.host(env.sourceId).advance(env.binding))
        assertEquals(1, env.local.drainCalls)
        assertEquals(1, env.artifacts.stores)
    }

    @Test
    fun `source cancellation rotates epoch and requires independent source reconciliation`() = runTest {
        val env = TransferEnv()
        env.quiesce()
        val previous = env.backend.current.fence
        env.source.cancel(env.binding).changed()
        assertEquals(OwnershipPhase.Reconciling, env.backend.current.phase)
        assertEquals(previous.epoch + 1, env.backend.current.fence.epoch)
        assertEquals(0, env.local.sourceCalls)
        env.local.reconciliationPending = true
        assertIs<OwnershipTransferResult.Waiting>(env.source.advance(env.binding))
        assertEquals(OwnershipPhase.Reconciling, env.backend.current.phase)
        env.local.reconciliationPending = false
        env.source.advance(env.binding).changed()
        assertEquals(OwnershipPhase.Active, env.backend.current.phase)
        assertEquals(env.sourceId, env.backend.current.fence.host)
        assertEquals(0, env.local.recipientCalls)
    }

    @Test
    fun `live native work and outstanding shared permits prevent checkpoint export`() = runTest {
        val env = TransferEnv()
        env.quiesce()
        env.local.executors = 1
        assertIs<OwnershipTransferResult.Waiting>(env.source.advance(env.binding))
        assertEquals(0, env.artifacts.stores)
        env.local.executors = 0
        val snapshot = env.backend.current
        val permit = OwnershipPermit("f".repeat(32), 1, snapshot.fence, OwnershipPermitKind.GitHub)
        env.backend.current = snapshot.copy(permits = listOf(permit), nextPermit = 2)
        assertIs<OwnershipTransferResult.Waiting>(env.source.advance(env.binding))
        assertEquals(0, env.artifacts.stores)
        assertEquals(OwnershipPhase.Quiescing, env.backend.current.phase)
    }

    @Test
    fun `recipient fresh reconciliation must positively resolve every unknown outcome`() = runTest {
        val env = TransferEnv()
        env.export()
        env.target.claim(env.binding).changed()
        env.local.unresolved = 1
        assertIs<OwnershipTransferResult.Waiting>(env.target.advance(env.binding))
        assertEquals(OwnershipPhase.Reconciling, env.backend.current.phase)
        env.local.unresolved = 0
        env.target.advance(env.binding).changed()
        assertEquals(OwnershipPhase.Active, env.backend.current.phase)
    }

    @Test
    fun `a stale local receipt cannot change another transfer`() = runTest {
        val env = TransferEnv()
        env.quiesce()
        env.local.wrongTransfer = true
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Receipt),
            env.source.advance(env.binding),
        )
        assertEquals(0, env.artifacts.stores)
        assertEquals(OwnershipPhase.Quiescing, env.backend.current.phase)
    }

    @Test
    fun `missing or mismatching checkpoint prevents target claim before consent`() = runTest {
        val env = TransferEnv()
        env.export()
        env.artifacts.hidden = true
        assertIs<OwnershipTransferResult.Unavailable>(env.target.claim(env.binding))
        assertEquals(listOf(OwnershipTransferAction.Begin), env.consent.actions)
        env.artifacts.hidden = false
        env.artifacts.corrupt = true
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Checkpoint),
            env.target.claim(env.binding),
        )
        assertEquals(OwnershipPhase.Exported, env.backend.current.phase)
    }

    @Test
    fun `host identity and independently verified source binding cannot be supplied by a stale caller`() = runTest {
        val env = TransferEnv()
        env.local.bindingValid = false
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Binding),
            env.source.begin(env.binding, env.targetId),
        )
        assertEquals(0, env.backend.reads)
        env.local.bindingValid = true
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Owner),
            env.target.begin(env.binding, env.sourceId),
        )
        assertEquals(0, env.backend.commits)
    }

    @Test
    fun `a supplied ownership key is independently checked against the canonical task before IO`() = runTest {
        val env = TransferEnv()
        val unrelated = env.binding.copy(key = OwnershipKey("f".repeat(64)))
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Binding),
            env.source.begin(unrelated, env.targetId),
        )
        assertEquals(0, env.backend.reads)
        assertTrue(env.consent.requests.isEmpty())
    }

    @Test
    fun `old source has no phase progression or cancellation path after target claim`() = runTest {
        val env = TransferEnv()
        env.export()
        env.target.claim(env.binding).changed()
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Owner),
            env.source.advance(env.binding),
        )
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Owner),
            env.source.cancel(env.binding),
        )
        assertEquals(0, env.local.sourceCalls)
    }

    @Test
    fun `unsupported local drain is explicit unavailable and cancellation propagates`() = runTest {
        val env = TransferEnv()
        env.quiesce()
        env.local.unavailable = true
        assertEquals(
            OwnershipTransferResult.Unavailable(OwnershipUnavailable.NotConfigured),
            env.source.advance(env.binding),
        )
        env.local.cancel = true
        assertFailsWith<CancellationException> { env.source.advance(env.binding) }
        assertEquals(OwnershipPhase.Quiescing, env.backend.current.phase)
    }

    @Test
    fun `unknown claim resumes recipient reconciliation without another claim or native consent`() = runTest {
        val env = TransferEnv()
        env.export()
        env.backend.unknownNext = true
        val result = env.target.claim(env.binding)
        assertEquals(
            OwnershipChange.Unavailable(OwnershipUnavailable.UnknownCommit),
            assertIs<OwnershipTransferResult.Coordinated>(result).change,
        )
        assertEquals(OwnershipPhase.Reconciling, env.backend.current.phase)
        env.host(env.targetId).advance(env.binding).changed()
        assertEquals(OwnershipPhase.Active, env.backend.current.phase)
        assertEquals(1, env.local.recipientCalls)
        assertEquals(2, env.consent.actions.size)
    }

    @Test
    fun `post drainage snapshot accounts for confirmed permit settlements before export CAS`() = runTest {
        val env = TransferEnv()
        env.quiesce()
        val snapshot = env.backend.current
        val permit = OwnershipPermit("f".repeat(32), 1, snapshot.fence, OwnershipPermitKind.Workflow)
        env.backend.current = snapshot.copy(permits = listOf(permit), nextPermit = 2)
        env.local.onDrain = {
            val current = env.backend.current
            env.backend.current = current.copy(permits = emptyList(), revision = current.revision + 1)
        }
        env.source.advance(env.binding).changed()
        assertEquals(OwnershipPhase.Exported, env.backend.current.phase)
        assertTrue(env.backend.current.permits.isEmpty())
    }

    @Test
    fun `artifact write must positively confirm the exact digest before export`() = runTest {
        val env = TransferEnv()
        env.quiesce()
        env.artifacts.wrongStoredDigest = true
        assertEquals(
            OwnershipTransferResult.Refused(OwnershipTransferRefusal.Checkpoint),
            env.source.advance(env.binding),
        )
        assertEquals(OwnershipPhase.Quiescing, env.backend.current.phase)
    }
}

private class TransferEnv {
    val sourceId = ExecutorHostId("a".repeat(32))
    val targetId = ExecutorHostId("b".repeat(32))
    private val digests = Sha256OwnershipTransferDigests()
    private val identity = GitHubTaskIdentity(
        GitHubRepository("aequicor", "Heartbeat"),
        GitHubTaskKind.Issue,
        475,
        GitHubNodeId("R_repository"),
        GitHubNodeId("I_task"),
    )
    val binding = OwnershipTransferBinding(
        identity,
        ProcessDefinition(ItemId("script"), 7),
        "e".repeat(64),
        OwnershipLocalProcess(HarnessId("harness"), ProcessId("process"), "lifetime"),
        "/workspace",
        digests.key(identity),
    )
    val backend = TransferBackend(ProcessOwnership(OwnershipFence(binding.key, sourceId, 1)))
    val manifest = assertIs<PortableProcessExport.Ready>(
        ProcessRecord(binding.process, Instant.fromEpochSeconds(1000), incarnation = binding.incarnation)
            .portableCheckpoint(
                binding.identity,
                PortableProcessReceipts(binding.definitionDigest, true, emptyMap(), 0, emptyList()),
            ),
    ).checkpoint
    val local = TransferLocal(backend, manifest)
    val artifacts = TransferArtifacts()
    val consent = TransferConsent()
    val source = host(sourceId)
    val target = host(targetId)

    fun host(actor: ExecutorHostId): OwnershipTransferHost = OwnershipTransferHost(
        actor,
        OwnershipCoordinatorClient(backend, actor),
        consent,
        local,
        artifacts,
        digests,
        { "d".repeat(32) },
    )

    suspend fun prepare() {
        source.begin(binding, targetId).changed()
    }

    suspend fun quiesce() {
        prepare()
        source.advance(binding).changed()
    }

    suspend fun export() {
        quiesce()
        source.advance(binding).changed()
    }
}

private class TransferBackend(var current: ProcessOwnership) : OwnershipBackend {
    var commits = 0
    var reads = 0
    var unknownNext = false

    override suspend fun read(key: OwnershipKey): OwnershipRead {
        reads++
        return OwnershipRead.Current(current.takeIf { it.fence.key == key })
    }

    override suspend fun compareAndSet(
        key: OwnershipKey,
        expected: ProcessOwnership?,
        next: ProcessOwnership,
    ): OwnershipCommit {
        commits++
        if (current != expected || current.fence.key != key) return OwnershipCommit.Conflict
        current = next
        return if (unknownNext) {
            unknownNext = false
            OwnershipCommit.Unavailable(OwnershipUnavailable.UnknownCommit)
        } else {
            OwnershipCommit.Applied
        }
    }
}

private class TransferConsent : OwnershipTransferConsent {
    var allowed = true
    var onConfirm: () -> Unit = {}
    val requests = mutableListOf<OwnershipTransferConsentRequest>()
    val actions get() = requests.map { it.action }

    override suspend fun confirm(request: OwnershipTransferConsentRequest): Boolean {
        requests += request
        onConfirm()
        return allowed
    }
}

private class TransferLocal(private val backend: TransferBackend, private val manifest: PortableProcessCheckpoint) :
    OwnershipTransferLocal {
    var bindingValid = true
    var closeCalls = 0
    var drainCalls = 0
    var sourceCalls = 0
    var recipientCalls = 0
    var executors = 0
    var unresolved = 0
    var wrongTransfer = false
    var reconciliationPending = false
    var unavailable = false
    var cancel = false
    var onDrain: () -> Unit = {}

    override suspend fun verifyBinding(binding: OwnershipTransferBinding): Boolean = bindingValid

    override suspend fun closeAdmissions(context: OwnershipTransferContext): OwnershipLocalResult<ProcessOwnership> {
        closeCalls++
        return OwnershipLocalResult.Confirmed(context.snapshot)
    }

    override suspend fun drainAndSeal(context: OwnershipTransferContext): OwnershipLocalResult<OwnershipDrainReceipt> {
        drainCalls++
        if (cancel) throw CancellationException("test cancellation")
        if (unavailable) return OwnershipLocalResult.Unavailable(OwnershipUnavailable.NotConfigured)
        onDrain()
        val snapshot = backend.current.let {
            if (wrongTransfer) it.copy(transfer = checkNotNull(it.transfer).copy(id = "f".repeat(32))) else it
        }
        return OwnershipLocalResult.Confirmed(OwnershipDrainReceipt(snapshot, manifest, executors, 0))
    }

    override suspend fun reconcileRecipient(
        context: OwnershipTransferContext,
        checkpoint: PortableProcessCheckpoint,
    ): OwnershipLocalResult<OwnershipReconciliationReceipt> {
        recipientCalls++
        assertEquals(manifest, checkpoint)
        return reconcile(context)
    }

    override suspend fun reconcileSource(
        context: OwnershipTransferContext,
    ): OwnershipLocalResult<OwnershipReconciliationReceipt> {
        sourceCalls++
        return reconcile(context)
    }

    private fun reconcile(context: OwnershipTransferContext): OwnershipLocalResult<OwnershipReconciliationReceipt> =
        if (reconciliationPending) {
            OwnershipLocalResult.Pending
        } else {
            OwnershipLocalResult.Confirmed(
                OwnershipReconciliationReceipt(
                    backend.current,
                    checkNotNull(context.snapshot.transfer).checkpoint,
                    unresolved,
                ),
            )
        }
}

private class TransferArtifacts : OwnershipTransferArtifacts {
    var stores = 0
    var hidden = false
    var corrupt = false
    var wrongStoredDigest = false
    private val stored = mutableMapOf<String, PortableProcessCheckpoint>()

    override suspend fun store(checkpoint: PortableProcessCheckpoint): OwnershipCheckpoint {
        stores++
        val digest = Json.encodeToString(checkpoint).encodeUtf8().sha256().hex()
        stored[digest] = checkpoint
        return if (wrongStoredDigest) {
            OwnershipCheckpoint("f".repeat(64), "f".repeat(64))
        } else {
            OwnershipCheckpoint(digest, digest)
        }
    }

    override suspend fun load(pointer: OwnershipCheckpoint): PortableProcessCheckpoint? {
        if (hidden) return null
        return stored[pointer.digest]?.let { if (corrupt) it.copy(definitionDigest = "f".repeat(64)) else it }
    }
}

private fun OwnershipTransferResult.changed(): ProcessOwnership {
    val change = assertIs<OwnershipChange.Current>(assertIs<OwnershipTransferResult.Coordinated>(this).change)
    assertTrue(change.isChanged)
    return change.record
}
