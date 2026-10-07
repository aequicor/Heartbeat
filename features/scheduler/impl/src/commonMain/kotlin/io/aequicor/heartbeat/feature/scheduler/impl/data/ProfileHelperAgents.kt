package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperLease
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadataLimits
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Profile-owned helper supervision; host metadata is durable, live leases and operation handles are ephemeral. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<HelperAgents>())
@ContributesBinding(ProfileScope::class, binding = binding<ScheduledHelperLeases>())
@Inject
internal class ProfileHelperAgents(
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val hosts: Lazy<Set<ScheduledSessionHost>>,
    private val capacity: ProfileBackgroundCapacity,
    private val results: ActionResults,
) : ScheduledHelperLeases,
    HelperLeaseRegistry {
    private val log = Log.tag("ProfileHelperAgents")
    private val lock = Mutex()
    private val recovering = mutableSetOf<HelperId>()
    private val bindings = mutableMapOf<HelperId, ManagedHelperLease>()

    override suspend fun canHost(parent: SessionRef?): Boolean = hosts.value.any { it.canHostHelper(parent) }

    override suspend fun acquire(owner: ActionId, parent: SessionRef?, existing: HelperId?): HelperLease {
        val reservation = newActionId()
        var isAcquired = false
        var isClaimed = false
        var isHandedOff = false
        var lease: ManagedHelperLease? = null
        try {
            currentCoroutineContext().ensureActive()
            if (existing != null) {
                lock.withLock {
                    check(existing !in bindings && recovering.add(existing)) { "Helper already has a live lease" }
                    isClaimed = true
                }
            }
            val restored = existing?.let { resolve(it) }
            val host = restored?.first ?: hosts.value.sortedByDescending { it.priority }
                .firstOrNull { it.canHostHelper(parent) }
                ?: error("No host can supervise this helper")
            restored?.second?.checkOwner(owner, parent)
            capacity.acquireHelper(reservation, owner, parent)
            isAcquired = true
            // Queueing may take time. Read persisted attempts again before binding recovered work.
            val metadata = existing?.let { resolve(it) }?.also {
                check(it.first === host) { "Helper host changed during recovery" }
                it.second.checkOwner(owner, parent)
            }?.second
            val created = ManagedHelperLease(
                HelperLeaseIdentity(owner, reservation, parent, existing, metadata?.lastRequest),
                host,
                this,
                profile,
                capacity,
            )
            if (existing != null) bind(existing, created)
            lease = created
            if (isClaimed) {
                lock.withLock {
                    recovering.remove(existing)
                    isClaimed = false
                }
            }
            currentCoroutineContext().ensureActive()
            isHandedOff = true
            return created
        } finally {
            if (!isHandedOff) {
                withContext(NonCancellable) {
                    if (isClaimed) lock.withLock { recovering.remove(existing) }
                    if (isAcquired) abandonAcquisition(reservation, lease)
                }
            }
        }
    }

    /** Transfers an already acquired scheduled reservation without consuming a second profile slot. */
    override suspend fun adoptScheduled(
        owner: ActionId,
        parent: SessionRef,
        existing: HelperId?,
        expectedRequest: RequestId?,
    ): ManagedHelperLease? {
        val restored = existing?.let { resolve(it) }
        restored?.second?.checkOwner(owner, parent)
        check(restored?.second?.lastRequest == null || restored.second.lastRequest == expectedRequest) {
            "Scheduled helper request does not match its journal"
        }
        val host = restored?.first ?: hosts.value.sortedByDescending { it.priority }
            .firstOrNull { it.canHostHelper(parent) } ?: return null
        val lease = ManagedHelperLease(
            HelperLeaseIdentity(owner, owner, parent, existing, restored?.second?.lastRequest),
            host,
            this,
            profile,
            capacity,
        )
        if (existing != null) bind(existing, lease)
        return lease
    }

    override suspend fun create(
        lease: HelperLease,
        workspace: WorkspaceRef?,
        target: EngineTarget?,
        title: String,
        trustCap: TrustLevel,
    ): HelperId {
        val owned = lease as? ManagedHelperLease
        check(owned != null && owned.service === this) { "Foreign helper lease" }
        return owned.create(HelperCreateRequest(owned.owner, owned.parent, workspace, target, title, trustCap))
    }

    override suspend fun prompt(helper: HelperId, prompt: HelperPrompt): HelperSubmission = bound(
        helper,
    ).prompt(helper, prompt)

    override suspend fun result(helper: HelperId, request: RequestId): HelperResult? {
        val lease = lock.withLock { bindings[helper] }
        if (lease != null) return lease.result(helper, request)
        val result = resolve(helper).first.helperResult(helper, request)
        check(result == null || result.request == request) { "Host returned a different helper result" }
        return result
    }

    override suspend fun cancel(helper: HelperId, request: RequestId): HelperCancellation =
        bound(helper).cancel(helper, request)

    override suspend fun isHelper(session: SessionRef): Boolean = hosts.value.any { it.isHelper(session) }

    @HighFrequency
    override suspend fun metadata(session: SessionRef): HelperMetadata? {
        log.v { "Read durable reverse helper identity" }
        val matches = hosts.value.mapNotNull { it.helperMetadata(session) }
        check(matches.size <= 1) { "Helper session ownership is ambiguous" }
        return matches.singleOrNull()?.also {
            check(it.session == session) { "Host returned different helper session metadata" }
        }
    }

    @HighFrequency
    override suspend fun owned(owner: ActionId, after: HelperId?, limit: Int): List<HelperMetadata> {
        log.v { "Read bounded durable helper owner page" }
        require(limit in 1..HelperMetadataLimits.MAX_PAGE_SIZE) { "Invalid helper metadata page size" }
        val pages = hosts.value.flatMap { host ->
            host.ownedHelpers(owner, after, limit).also { page ->
                check(page.size <= limit && page.all { it.owner == owner }) { "Invalid helper owner page" }
                check(page.all { after == null || it.id.value > after.value }) { "Invalid helper page boundary" }
                check(page.zipWithNext().all { (a, b) -> a.id.value < b.id.value }) { "Unordered helper page" }
            }
        }
        check(pages.map { it.id }.distinct().size == pages.size) { "Helper identity belongs to multiple hosts" }
        return pages.sortedBy { it.id.value }.take(limit)
    }

    override suspend fun finish(action: ActionId, payload: String) = results.finish(action, payload)

    private suspend fun abandonAcquisition(reservation: ActionId, lease: ManagedHelperLease?) {
        if (lease == null) capacity.release(reservation) else lease.scheduleRelease()
    }

    private suspend fun bound(id: HelperId): ManagedHelperLease = lock.withLock {
        checkNotNull(bindings[id]) { "Helper needs an active lease" }
    }

    override suspend fun bind(id: HelperId, lease: ManagedHelperLease) {
        lock.withLock {
            check(id !in bindings && (id !in recovering || lease.restoredHelper == id)) {
                "Helper already has a live lease"
            }
            bindings[id] = lease
        }
    }

    override suspend fun unbind(id: HelperId, lease: ManagedHelperLease) {
        lock.withLock { if (bindings[id] === lease) bindings.remove(id) }
    }

    private suspend fun resolve(id: HelperId): Pair<ScheduledSessionHost, HelperMetadata> {
        val matches = hosts.value.mapNotNull { host -> host.helperMetadata(id)?.let { host to it } }
        val match = checkNotNull(matches.singleOrNull()) { "Helper host is missing or ambiguous" }
        check(match.second.id == id) { "Host returned different helper metadata" }
        return match
    }
}

private fun HelperMetadata.checkOwner(owner: ActionId, parent: SessionRef?) {
    check(this.owner == owner && this.parent == parent) { "Helper recovery ownership does not match" }
}
