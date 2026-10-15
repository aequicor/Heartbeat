package io.aequicor.heartbeat.feature.harness.impl.data.script

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.rethrowFatalHarnessFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.loadDependencies

/**
 * Profile-owned harness code host. K2 compiles in a killable worker process ([ProcessHarnessCompiler]) under the
 * profile's single compiler permit; the jar it writes is cached here and evaluated in the app. Cache changes cannot
 * alter bytecode held by a live artifact lease.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class JvmHarnessScriptHost(
    cacheDirectory: Path,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val compiler: HarnessKotlinCompiler = ProcessHarnessCompiler(scope, dispatchers.io, dispatchers.default),
) : HarnessScriptHost {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) stores: DataStores,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        dispatchers: DispatcherProvider,
        compiler: HarnessKotlinCompiler,
    ) : this(
        Paths.get(stores.filesDirectory("harness_cache")),
        System.getProperty("heartbeat.app.version") ?: "development",
        scope.coroutineScope,
        dispatchers,
        compiler,
    )

    override val isAvailable: Boolean = true

    private val log = Log.tag("HarnessScriptHost")
    private val queue = HarnessCompilationQueue()
    private val cache = HarnessScriptCache(cacheDirectory)
    private val classpath = HarnessScriptClasspath()
    private val lock = Mutex()
    private val pending = mutableMapOf<String, MutableSet<AtomicBoolean>>()
    private var isCachePrepared = false

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
        val item = cache.itemKey(request)
        val invalidated = AtomicBoolean(false)
        synchronized(pending) { pending.getOrPut(item) { mutableSetOf() }.add(invalidated) }
        var isPrepared = false
        val preparation = try {
            prepareCompiler(request).also { isPrepared = it == null }
        } finally {
            if (!isPrepared) releasePending(item, invalidated)
        }
        if (preparation != null) return preparation
        val ticket = HarnessCompilationTicket()
        val work = scope.async(dispatchers.io) {
            queue.run(request.isAgentInitiated) {
                if (!ticket.admit()) throw CancellationException("Compilation waiter left before admission")
                compileOwned(request, item, invalidated).also(ticket::publish)
            }
        }
        // Completion cleanup must also run when the profile was already cancelled before async started.
        work.invokeOnCompletion { releasePending(item, invalidated) }
        try {
            return select {
                work.onAwait { ticket.claim() }
                onTimeout(COMPILATION_WAIT_MILLIS) { HarnessCompilationResult.TimedOut }
            }
        } finally {
            // A waiter that leaves before its permit withdraws the queued request; an admitted compiler is detached.
            if (ticket.drop()) work.cancel()
        }
    }

    private fun releasePending(item: String, invalidated: AtomicBoolean) {
        synchronized(pending) {
            pending[item]?.let { tokens ->
                tokens.remove(invalidated)
                if (tokens.isEmpty()) pending.remove(item)
            }
        }
    }

    /** A valid cached artifact needs only the evaluator; network preparation does not consume the compile budget. */
    private suspend fun prepareCompiler(request: HarnessCompilationRequest): HarnessCompilationResult.Failure? {
        val preparing = compiler as? PreparingHarnessCompiler ?: return null
        val work = scope.async(dispatchers.io) { prepareCompilerOwned(request, preparing) }
        return try {
            work.await()
        } finally {
            // Profile closure or a departing waiter releases this awaiter, while the app still owns the download.
            work.cancel()
        }
    }

    private suspend fun prepareCompilerOwned(
        request: HarnessCompilationRequest,
        preparing: PreparingHarnessCompiler,
    ): HarnessCompilationResult.Failure? = try {
        val key = classpath.key(request, appVersion)
        val isReusable = lock.withLock {
            prepareCache()
            readCache(cache.itemKey(request), key, request.kind)?.let {
                it.code.close()
                true
            } ?: false
        }
        if (!isReusable) preparing.prepare()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.rethrowFatalHarnessFailure()
        log.w(error.safeHarnessFailure()) { "Compiler components could not be prepared" }
        HarnessCompilationResult.Failure(
            listOf(
                HarnessCodeDiagnostic(
                    HarnessDiagnosticSeverity.Error,
                    "Compiler components are unavailable. Check your connection and free disk space, then retry.",
                ),
            ),
        )
    }

    private suspend fun compileOwned(
        request: HarnessCompilationRequest,
        item: String,
        invalidated: AtomicBoolean,
    ): HarnessCompilationResult = try {
        log.v { "compile harness code" }
        val key = classpath.key(request, appVersion)
        val cached = lock.withLock {
            prepareCache()
            if (invalidated.get()) null else readCache(item, key, request.kind)
        }
        if (cached != null) {
            HarnessCompilationResult.Success(cached.code, cached.warnings)
        } else {
            compileFresh(request, item, key, invalidated)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.rethrowFatalHarnessFailure()
        log.w(error.safeHarnessFailure()) { "compiler operation failed" }
        HarnessCompilationResult.Failure(harnessHostFailure())
    }

    /** Called under [lock]: temporary outputs of a crashed earlier compiler are never live in a new host. */
    private fun prepareCache() {
        if (isCachePrepared) return
        isCachePrepared = true
        try {
            cache.purgeTemporary()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.safeHarnessFailure()) { "stale compiler outputs remain in the cache" }
        }
    }

    private fun readCache(item: String, key: String, kind: HarnessCodeKind): CachedHarnessCode? = try {
        cache.load(item, key, kind)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.rethrowFatalHarnessFailure()
        log.w(error.safeHarnessFailure()) { "recompile cached code" }
        cache.remove(item)
        null
    }

    private suspend fun compileFresh(
        request: HarnessCompilationRequest,
        item: String,
        key: String,
        invalidated: AtomicBoolean,
    ): HarnessCompilationResult {
        if (invalidated.get()) return HarnessCompilationResult.Failure(harnessHostFailure())
        val output = cache.newOutput()
        try {
            val outcome = compiler.compile(request.kind, request.source, output)
            currentCoroutineContext().ensureActive()
            return when (outcome) {
                is HarnessCompilerOutcome.Compiled -> lock.withLock {
                    if (invalidated.get()) {
                        HarnessCompilationResult.Failure(harnessHostFailure())
                    } else {
                        val cached = cache.store(item, key, request.kind, output, outcome.warnings)
                        HarnessCompilationResult.Success(cached.code, cached.warnings)
                    }
                }

                is HarnessCompilerOutcome.Failed -> HarnessCompilationResult.Failure(outcome.diagnostics)

                HarnessCompilerOutcome.TimedOut -> HarnessCompilationResult.TimedOut

                HarnessCompilerOutcome.Unavailable -> HarnessCompilationResult.Failure(harnessHostFailure())
            }
        } finally {
            // The compiler returns only after its worker answered or died: nothing writes this file any more.
            cache.discardOutput(output)
        }
    }

    override suspend fun evaluate(
        code: CompiledHarnessCode,
        context: HarnessEvaluationContext,
    ): HarnessEvaluationResult {
        val borrowed = code.retain()
        try {
            log.v { "evaluate harness code" }
            val argument = when (context) {
                is HarnessEvaluationContext.Script -> {
                    require(code.kind == HarnessCodeKind.Script)
                    context.scope
                }

                is HarnessEvaluationContext.Workflow -> {
                    require(code.kind == HarnessCodeKind.Workflow)
                    context.registration
                }
            }
            val (script, loader) = (borrowed as HarnessMemoryCode).content()
            val result = BasicJvmScriptEvaluator()(
                script,
                ScriptEvaluationConfiguration {
                    constructorArgs(argument)
                    jvm {
                        baseClassLoader(loader)
                        loadDependencies(false)
                    }
                },
            )
            val failure = (result as? ResultWithDiagnostics.Success)?.value?.returnValue as? ResultValue.Error
            failure?.error?.rethrowFatalHarnessFailure()
            result.reports.forEach { it.exception?.rethrowFatalHarnessFailure() }
            currentCoroutineContext().ensureActive()
            return when {
                result is ResultWithDiagnostics.Failure ->
                    harnessEvaluationFailure(result.reports.mapNotNull { it.exception })

                failure != null -> harnessEvaluationFailure(listOf(failure.error))

                else -> HarnessEvaluationResult.Success
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.rethrowFatalHarnessFailure()
            log.w(error.safeHarnessFailure()) { "evaluation operation failed" }
            return harnessEvaluationFailure(listOf(error))
        } finally {
            borrowed.close()
        }
    }

    override suspend fun removeCached(harness: HarnessId, item: ItemId) = withContext(dispatchers.io) {
        log.v { "remove harness cache" }
        val key = cache.itemKey(HarnessCompilationRequest(harness, item, HarnessCodeKind.Script, ""))
        lock.withLock {
            synchronized(pending) { pending[key]?.forEach { it.set(true) } }
            cache.remove(key)
        }
    }

    override suspend fun retainCached(items: Set<Pair<HarnessId, ItemId>>) = withContext(dispatchers.io) {
        val retained = items.mapTo(mutableSetOf()) { (harness, item) ->
            cache.itemKey(HarnessCompilationRequest(harness, item, HarnessCodeKind.Script, ""))
        }
        val removed = lock.withLock {
            prepareCache()
            cache.retainOnly(retained + synchronized(pending) { pending.keys.toSet() })
        }
        if (removed > 0) log.d { "removed $removed orphaned harness cache files" }
    }
}

private const val COMPILATION_WAIT_MILLIS = 90_000L

/** Compiler/evaluated code exceptions may embed source, paths or secrets; never attach their cause. */
internal fun Exception.safeHarnessFailure(): IllegalStateException = IllegalStateException(
    if (this is java.io.IOException) "Harness code storage unavailable" else "Harness code operation failed",
)
