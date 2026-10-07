package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperLease
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadata
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import kotlinx.coroutines.CoroutineScope

internal class HarnessSpawnFixture(scope: CoroutineScope) {
    val trace = mutableListOf<String>()
    val journal = SpawnTestJournal(trace)
    val helpers = SpawnTestHelpers(trace)
    val bindings = SpawnTestBindings(trace)
    val operations = HarnessSpawnOperations(scope, journal, lazyOf(helpers), bindings)
    val record = HarnessSpawnRecord(
        ActionId("slot"),
        ActionId("spawn"),
        HarnessSpawnOwner(HarnessId("harness"), ItemId("script"), 7),
        dispatchSession,
        RequestId("R"),
        RequestId("attach"),
        HarnessCallOrigin(),
    )
    var isAdmitted = true

    fun submission(record: HarnessSpawnRecord = this.record): HarnessSpawnSubmission = HarnessSpawnSubmission(
        record,
        null,
        "Private title",
        HelperPrompt(record.request, "Private prompt"),
    ) { isAdmitted }
}

internal class SpawnTestJournal(private val trace: MutableList<String>) : HarnessSpawnJournal {
    val records = mutableMapOf<ActionId, HarnessSpawnRecord>()
    var beforeRead: suspend () -> Unit = {}
    var beforeGrant: suspend () -> Unit = {}
    var afterGrant: suspend () -> Unit = {}
    var beforeBind: suspend () -> Unit = {}
    var beforeSettle: suspend () -> Unit = {}
    var reads = 0

    override suspend fun pending(): List<HarnessSpawnRecord> {
        reads++
        beforeRead()
        return records.values.toList()
    }

    override suspend fun recordGranted(record: HarnessSpawnRecord) {
        beforeGrant()
        records[record.reservation] = record
        trace += "grant"
        afterGrant()
    }

    override suspend fun bindHelper(reservation: ActionId, helper: HelperId): HarnessSpawnRecord {
        beforeBind()
        val bound = checkNotNull(records[reservation]).copy(helper = helper)
        records[reservation] = bound
        trace += "checkpoint"
        return bound
    }

    override suspend fun settle(record: HarnessSpawnRecord) {
        beforeSettle()
        trace += "settle"
        records.remove(record.reservation)
    }
}

internal class SpawnTestBindings(private val trace: MutableList<String>) : HarnessHelperBindings {
    val bound = mutableListOf<HarnessHelperBinding>()
    override suspend fun bind(binding: HarnessHelperBinding) {
        bound += binding
        trace += "binding"
    }
    override suspend fun lookup(helper: HelperId): HarnessHelperBinding? = bound.singleOrNull { it.helper == helper }
    override suspend fun bindSession(helper: HelperId, owner: ActionId, session: SessionRef): HarnessHelperBinding =
        error("Not used by producer")
}

internal data class SpawnTestAcquisition(
    val owner: ActionId,
    val parent: SessionRef?,
    val helper: HelperId?,
    val slot: ActionId?,
)

internal class SpawnTestHelpers(private val trace: MutableList<String>) : HelperAgents {
    val acquisitions = mutableListOf<SpawnTestAcquisition>()
    val leases = mutableListOf<SpawnTestLease>()
    val prompts = mutableListOf<HelperPrompt>()
    val helper = HelperId("helper")
    var beforeAcquire: suspend () -> Unit = {}
    var beforeCreate: suspend () -> Unit = {}
    var beforePrompt: suspend () -> Unit = {}
    var beforeResult: suspend () -> Unit = {}
    var result: HelperResult? = null
    var release: suspend () -> HelperReleaseResult = { HelperReleaseResult.Released }
    var submission: (HelperPrompt) -> HelperSubmission = { HelperSubmission.Accepted(it.request, dispatchSession) }
    var creates = 0
    var releases = 0

    override suspend fun acquire(
        owner: ActionId,
        parent: SessionRef?,
        existing: HelperId?,
        reservation: ActionId?,
    ): HelperLease {
        beforeAcquire()
        acquisitions += SpawnTestAcquisition(owner, parent, existing, reservation)
        trace += "acquire"
        return SpawnTestLease(owner) {
            releases++
            trace += "release"
            release()
        }.also(leases::add)
    }

    override suspend fun create(
        lease: HelperLease,
        workspace: WorkspaceRef?,
        target: EngineTarget?,
        title: String,
        trustCap: TrustLevel,
    ): HelperId {
        check(trustCap == TrustLevel.Ask && target == null)
        beforeCreate()
        creates++
        trace += "create"
        return helper
    }

    override suspend fun prompt(helper: HelperId, prompt: HelperPrompt): HelperSubmission {
        beforePrompt()
        prompts += prompt
        trace += "prompt"
        return submission(prompt)
    }

    override suspend fun result(helper: HelperId, request: RequestId): HelperResult? {
        beforeResult()
        return result
    }

    override suspend fun canHost(parent: SessionRef?): Boolean = true
    override suspend fun cancel(helper: HelperId, request: RequestId): HelperCancellation = error("Use lease barrier")
    override suspend fun isHelper(session: SessionRef): Boolean = false
    override suspend fun metadata(session: SessionRef): HelperMetadata? = null
    override suspend fun owned(owner: ActionId, after: HelperId?, limit: Int): List<HelperMetadata> = emptyList()
    override suspend fun finish(action: ActionId, payload: String): Unit = error("Not a workflow")
}

internal class SpawnTestLease(override val owner: ActionId, private val release: suspend () -> HelperReleaseResult) :
    HelperLease {
    override suspend fun release(): HelperReleaseResult = release.invoke()
}
