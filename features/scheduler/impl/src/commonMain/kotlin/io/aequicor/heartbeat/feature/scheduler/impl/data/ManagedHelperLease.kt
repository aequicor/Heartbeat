package io.aequicor.heartbeat.feature.scheduler.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperLease
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runtime ownership of one capacity reservation. Closing is irreversible; uncertain work keeps the reservation.
 * The mutex registers operations only; host IO runs in profile jobs outside it, so caller cancellation cannot
 * orphan an accepted prompt. Cleanup revokes pending submission through the host barrier before cancelling
 * its observation job.
 */
internal class ManagedHelperLease(
    private val identity: HelperLeaseIdentity,
    val host: ScheduledSessionHost,
    val service: HelperLeaseRegistry,
    private val profile: ScopeHandle,
    private val capacity: ProfileBackgroundCapacity,
) : HelperLease {
    override val owner: ActionId get() = identity.owner
    val parent: SessionRef? get() = identity.parent
    val restoredHelper: HelperId? get() = identity.helper
    private val log = Log.tag("ManagedHelperLease")
    private val lock = Mutex()
    private var helper = identity.helper
    private var request = identity.request
    private var operation: Deferred<*>? = null
    private var creation: Deferred<HelperId>? = null
    private var lastPrompt: HelperPrompt? = null
    private var submission: Deferred<HelperSubmission>? = null
    private var cleanup: Deferred<HelperReleaseResult>? = null
    private var unverifiedHelper: HelperId? = null
    private var isClosing = false
    private var isReleased = false

    suspend fun create(input: HelperCreateRequest): HelperId {
        currentCoroutineContext().ensureActive()
        val task = lock.withLock {
            check(!isClosing) { "Helper lease is closing" }
            check(helper == null && creation == null) { "Helper lease already created a chat" }
            owned {
                val id = host.createHelper(input)
                lock.withLock { unverifiedHelper = id }
                adoptCreated(id)
                check(lock.withLock { request == null }) { "Helper creation unexpectedly submitted native work" }
                log.i { "helper chat created for $owner" }
                id
            }.also {
                creation = it
                operation = it
                it.start()
            }
        }
        return task.await()
    }

    suspend fun prompt(id: HelperId, next: HelperPrompt): HelperSubmission {
        currentCoroutineContext().ensureActive()
        val task = lock.withLock {
            check(!isClosing && helper == id) { "Helper lease is unavailable" }
            val previous = lastPrompt
            if (previous?.request == next.request) {
                check(previous == next) { "Helper request is immutable" }
                return@withLock checkNotNull(submission)
            }
            check(operation?.isCompleted != false && request == null) { "Previous helper request is unresolved" }
            request = next.request
            lastPrompt = next
            owned {
                val result = host.promptHelper(id, next)
                check(result.request == next.request) { "Host returned a different helper request" }
                if (result is HelperSubmission.NotSubmitted) settled(next.request)
                result
            }.also {
                submission = it
                operation = it
                it.start()
            }
        }
        return task.await()
    }

    /** Result inspection can race acceptance, but never allows a new prompt until that operation also finishes. */
    suspend fun result(id: HelperId, requested: RequestId): HelperResult? {
        val result = host.helperResult(id, requested)
        check(result == null || result.request == requested) { "Host returned a different helper result" }
        if (result != null) settled(requested)
        return result
    }

    suspend fun cancel(id: HelperId, requested: RequestId): HelperCancellation {
        currentCoroutineContext().ensureActive()
        val task = lock.withLock {
            check(!isClosing && helper == id) { "Helper lease is closing or unavailable" }
            val pending = submission.takeIf { lastPrompt?.request == requested }
            owned {
                val barrier = cancelAtHost(id, requested)
                if (barrier !is HelperCancellation.Unconfirmed) pending?.cancelAndJoin()
                barrier
            }.also {
                operation = it
                it.start()
            }
        }
        return task.await()
    }

    override suspend fun release(): HelperReleaseResult = scheduleRelease().await()

    /** Registers cleanup even when the acquiring caller lost the lease at a cancellation boundary. */
    suspend fun scheduleRelease(): Deferred<HelperReleaseResult> = lock.withLock {
        cleanup?.takeIf { !it.isCompleted || isReleased }?.let { return@withLock it }
        isClosing = true
        val creating = creation
        owned {
            creating?.join()
            closeSafely()
        }.also {
            cleanup = it
            it.start()
        }
    }

    private suspend fun closeSafely(): HelperReleaseResult = try {
        lock.withLock { unverifiedHelper }?.let { adoptCreated(it) }
        val current = lock.withLock { helper to request }
        val id = current.first
        val pending = current.second
        if (id != null && pending != null && result(id, pending) == null) {
            if (cancelAtHost(id, pending) is HelperCancellation.Unconfirmed) {
                return HelperReleaseResult.Unconfirmed
            }
        }
        // A confirmed barrier prevents even a delayed send of this request. Its observer can now be cancelled.
        lock.withLock { listOfNotNull(submission, operation).distinct() }.forEach { it.cancelAndJoin() }
        // Finish the short ownership transfer even if the profile closes now.
        withContext(NonCancellable) {
            capacity.release(identity.reservation)
            if (id != null) service.unbind(id, this@ManagedHelperLease)
            lock.withLock { isReleased = true }
        }
        log.i { "helper lease released for $owner" }
        HelperReleaseResult.Released
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "helper termination is unconfirmed; capacity retained" }
        HelperReleaseResult.Unconfirmed
    }

    private suspend fun adoptCreated(id: HelperId) {
        val metadata = checkNotNull(host.helperMetadata(id)) { "Host did not persist helper metadata" }
        check(metadata.id == id && metadata.owner == owner && metadata.parent == parent) {
            "Host persisted different helper ownership"
        }
        service.bind(id, this)
        lock.withLock {
            helper = id
            request = metadata.lastRequest
            unverifiedHelper = null
        }
    }

    private suspend fun cancelAtHost(id: HelperId, requested: RequestId): HelperCancellation {
        val result = host.cancelHelper(id, requested)
        when (result) {
            is HelperCancellation.NotSubmitted -> {
                check(result.request == requested) { "Host cancelled a different helper request" }
                settled(requested)
            }

            is HelperCancellation.Terminal -> {
                check(result.result.request == requested) { "Host cancelled a different helper turn" }
                settled(requested)
            }

            is HelperCancellation.Unconfirmed -> {
                check(result.request == requested) { "Host inspected a different helper request" }
            }
        }
        return result
    }

    private suspend fun settled(completed: RequestId) {
        lock.withLock { if (request == completed) request = null }
    }

    private fun <T> owned(block: suspend () -> T): Deferred<T> =
        profile.coroutineScope.async(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "helper operation failed" }
                throw e
            }
        }
}

internal data class HelperLeaseIdentity(
    val owner: ActionId,
    val reservation: ActionId,
    val parent: SessionRef?,
    val helper: HelperId?,
    val request: RequestId?,
)

/** Short registry mutations; never calls host IO while holding the registry lock. */
internal interface HelperLeaseRegistry {
    suspend fun bind(id: HelperId, lease: ManagedHelperLease)
    suspend fun unbind(id: HelperId, lease: ManagedHelperLease)
}
