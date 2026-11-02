package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Profile-owned directory of independent DSH processes, one per native session. */
internal class DeepSeekRuntimeGroup(
    override val identity: RuntimeIdentity,
    trees: SessionTrees,
    private val open: suspend (suspend (DeepSeekRuntime) -> Unit) -> DeepSeekRuntime,
) : EngineRuntime,
    CreatesSessions,
    AttachesSessions {
    override val features = DeepSeekFeatures(CreatesSessions to this, AttachesSessions to this, SessionTrees to trees)
    private val closed = AtomicBoolean(false)
    override val isClosed: Boolean get() = closed.get()
    private val log = Log.tag("DeepSeekRuntimeGroup")
    private val mutex = Mutex()
    private val children = mutableMapOf<SessionRef, DeepSeekRuntime>()
    private val opening = mutableSetOf<DeepSeekRuntime>()
    private val openingJobs = mutableSetOf<Job>()
    private val attaching = mutableMapOf<SessionRef, Mutex>()

    override suspend fun create(request: CreateSessionRequest): ActiveSession = child { it.create(request) }

    override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession {
        val lock = mutex.withLock { attaching.getOrPut(ref) { Mutex() } }
        return lock.withLock {
            val existing = mutex.withLock { children[ref] }
            if (existing != null) {
                if (!existing.isClosed) {
                    try {
                        return@withLock existing.attach(ref, request)
                    } catch (error: EngineException) {
                        if (!existing.isClosed) throw error
                        log.w(error) { "DSH session retired during attachment; wait for process closure" }
                    }
                }
                existing.awaitRetired()
            }
            child { it.attach(ref, request) }
        }
    }

    private suspend fun child(start: suspend (DeepSeekRuntime) -> ActiveSession): ActiveSession {
        val job = checkNotNull(currentCoroutineContext()[Job])
        mutex.withLock {
            ensureOpen()
            openingJobs.add(job)
        }
        var runtime: DeepSeekRuntime? = null
        var isTransferred = false
        try {
            val opened = open(::forget)
            runtime = opened
            mutex.withLock {
                ensureOpen()
                opening.add(opened)
            }
            val lease = start(opened)
            mutex.withLock {
                ensureOpen()
                check(children[lease.ref]?.isClosed != false) { "Native session already attached" }
                children[lease.ref] = opened
                opening.remove(opened)
            }
            isTransferred = true
            return lease
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { openingJobs.remove(job) }
                if (!isTransferred) runtime?.close()
            }
        }
    }

    private suspend fun forget(runtime: DeepSeekRuntime) {
        mutex.withLock {
            opening.remove(runtime)
            children.entries.removeAll { it.value === runtime }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        val (snapshot, jobs) = mutex.withLock {
            val active = (children.values + opening).distinct()
            val pending = openingJobs.toList()
            children.clear()
            opening.clear()
            active to pending
        }
        val ownJob = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            val pending = jobs.filter { it !== ownJob }
            pending.forEach { it.cancel() }
            var failure: Exception? = null
            snapshot.forEach {
                try {
                    try {
                        it.close()
                    } finally {
                        withContext(NonCancellable) { it.awaitRetired() }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(EngineException(dshEngineFailure(error, null))) { "Could not close a DSH process" }
                    if (failure == null) failure = error
                }
            }
            pending.joinAll()
            failure?.let { throw it }
        }
    }

    private fun ensureOpen() {
        if (isClosed) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
    }
}
