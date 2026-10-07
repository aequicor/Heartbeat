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
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
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
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.loadDependencies
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/** Profile-owned K2 compiler; cache changes cannot alter bytecode held by a live artifact lease. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class JvmHarnessScriptHost(
    cacheDirectory: Path,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : HarnessScriptHost {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) stores: DataStores,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        dispatchers: DispatcherProvider,
    ) : this(
        Paths.get(stores.filesDirectory("harness_cache")),
        System.getProperty("heartbeat.app.version") ?: "development",
        scope.coroutineScope,
        dispatchers,
    )

    override val isAvailable: Boolean = true

    private val log = Log.tag("HarnessScriptHost")
    private val queue = HarnessCompilationQueue()
    private val cache = HarnessScriptCache(cacheDirectory)
    private val classpath = HarnessScriptClasspath()
    private val lock = Mutex()
    private val pending = mutableMapOf<String, MutableSet<AtomicBoolean>>()

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
        val failures = validateHarnessSource(request.source)
        if (failures.isNotEmpty()) return HarnessCompilationResult.Failure(failures)
        val item = cache.itemKey(request)
        val invalidated = AtomicBoolean(false)
        synchronized(pending) { pending.getOrPut(item) { mutableSetOf() }.add(invalidated) }
        val ticket = HarnessCompilationTicket()
        val work = scope.async(dispatchers.io) {
            queue.run(request.isAgentInitiated) { compileOwned(request, item, invalidated).also(ticket::publish) }
        }
        // Completion cleanup must also run when the profile was already cancelled before async started.
        work.invokeOnCompletion {
            synchronized(pending) {
                pending[item]?.let { tokens ->
                    tokens.remove(invalidated)
                    if (tokens.isEmpty()) pending.remove(item)
                }
            }
        }
        try {
            return select {
                work.onAwait { ticket.claim() }
                onTimeout(COMPILATION_WAIT_MILLIS) { HarnessCompilationResult.TimedOut }
            }
        } finally {
            ticket.drop()
        }
    }

    private suspend fun compileOwned(
        request: HarnessCompilationRequest,
        item: String,
        invalidated: AtomicBoolean,
    ): HarnessCompilationResult = try {
        log.v { "compile harness code" }
        val key = classpath.key(request, appVersion)
        val cached = lock.withLock { if (invalidated.get()) null else readCache(item, key, request.kind) }
        if (cached != null) {
            HarnessCompilationResult.Success(cached.code, cached.warnings)
        } else {
            compileFresh(request, item, key, invalidated)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.safeHarnessFailure()) { "compiler operation failed" }
        HarnessCompilationResult.Failure(harnessHostFailure())
    }

    private fun readCache(item: String, key: String, kind: HarnessCodeKind): CachedHarnessCode? = try {
        cache.load(item, key, kind)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
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
        val result = BasicJvmScriptingHost().compiler(
            StringScriptSource(request.source, "HarnessCode.kts"),
            harnessCompilerConfiguration(request.kind, classpath),
        )
        currentCoroutineContext().ensureActive()
        return when (result) {
            is ResultWithDiagnostics.Failure -> HarnessCompilationResult.Failure(result.reports.harnessDiagnostics())

            is ResultWithDiagnostics.Success -> lock.withLock {
                if (invalidated.get()) {
                    HarnessCompilationResult.Failure(harnessHostFailure())
                } else {
                    val cached = cache.store(
                        item,
                        key,
                        request.kind,
                        result.value as KJvmCompiledScript,
                        result.reports.harnessDiagnostics(),
                    )
                    HarnessCompilationResult.Success(cached.code, cached.warnings)
                }
            }
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
            val result = BasicJvmScriptingHost().evaluator(
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
            (failure?.error as? CancellationException)?.let { throw it }
            currentCoroutineContext().ensureActive()
            return when (result) {
                is ResultWithDiagnostics.Failure -> HarnessEvaluationResult.Failure(result.reports.harnessDiagnostics())

                is ResultWithDiagnostics.Success -> if (result.value.returnValue is ResultValue.Error) {
                    HarnessEvaluationResult.Failure(harnessHostFailure())
                } else {
                    HarnessEvaluationResult.Success
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.safeHarnessFailure()) { "evaluation operation failed" }
            return HarnessEvaluationResult.Failure(harnessHostFailure())
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
}

private const val COMPILATION_WAIT_MILLIS = 90_000L

/** Compiler/evaluated code exceptions may embed source, paths or secrets; never attach their cause. */
private fun Exception.safeHarnessFailure(): IllegalStateException = IllegalStateException(
    if (this is java.io.IOException) "Harness code storage unavailable" else "Harness code operation failed",
)
