package io.aequicor.heartbeat.feature.plantumlsupport.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlEnabled
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlSource
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.plantUmlPreamble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Rendering policy around the blocking [engine]: the toggle, source and image limits, a result cache, one shared
 * drawing per request, and time limits. Drawings run in the application [scope] on the single-threaded [worker], so a
 * caller that leaves (a row scrolled away) still gets the cached result later. A caller waits at most one time limit
 * for its drawing to start (else busy) and one more for it to finish (else timeout). A started drawing that outlives
 * its limit cannot be interrupted reliably; while it still runs — whether or not anyone still waits for it — new
 * requests fail fast as busy instead of queueing behind it. [timeSource] measures how long the current drawing runs.
 */
internal class DefaultPlantUmlRenderer(
    private val engine: PlantUmlEngine,
    private val toggles: FeatureToggles,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val limits: PlantUmlLimits = PlantUmlLimits(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : PlantUmlRenderer {
    private val lock = Mutex()
    private val cache = PlantUmlResultCache(limits.cacheEntries, limits.cacheBytes)
    private val drawings = mutableMapOf<PlantUmlRequest, Drawing>()

    /** When the drawing on the worker started, or null while the worker is idle. Guarded by [lock]. */
    private var running: TimeMark? = null

    override val availability: Flow<Boolean> = toggles.observe(PlantUmlEnabled)

    override suspend fun render(request: PlantUmlRequest): PlantUmlResult {
        if (!toggles.get(PlantUmlEnabled)) return PlantUmlResult.Unsupported
        if (request.source.length > limits.maxSourceCharacters) {
            // Rows ask again whenever they come back into view: deterministic refusals are VERBOSE.
            log.v { "PlantUML source of ${request.source.length} characters exceeds the limit" }
            return PlantUmlResult.Failed(PlantUmlFailure.TooLarge)
        }
        val source = PlantUmlSource.parse(request.source) ?: return unsupportedType()
        val key = request.copy(scale = request.scale.coerceIn(1f, limits.maxScale))
        val drawing = lock.withLock {
            cache[key]?.let { cached ->
                log.v { "PlantUML cache hit" }
                return cached
            }
            if (running?.let { it.elapsedNow() >= limits.timeout } == true) {
                log.v { "PlantUML engine still busy with a timed out drawing" }
                return PlantUmlResult.Failed(PlantUmlFailure.Busy)
            }
            drawings.getOrPut(key) { start(source, key) }
        }
        if (withTimeoutOrNull(limits.timeout) { drawing.started.await() } == null) {
            log.d { "PlantUML drawing did not start within ${limits.timeout}" }
            return PlantUmlResult.Failed(PlantUmlFailure.Busy)
        }
        return withTimeoutOrNull(limits.timeout) { drawing.result.await() } ?: run {
            log.w { "PlantUML drawing exceeded ${limits.timeout}; the engine stays busy until it finishes" }
            PlantUmlResult.Failed(PlantUmlFailure.Timeout)
        }
    }

    private fun unsupportedType(): PlantUmlResult {
        log.v { "PlantUML diagram type is not drawn in-process" }
        return PlantUmlResult.Unsupported
    }

    private fun start(source: PlantUmlSource, request: PlantUmlRequest): Drawing {
        val started = CompletableDeferred<Unit>()
        val result = scope.async(worker) {
            started.complete(Unit)
            draw(source, request)
        }
        return Drawing(started, result)
    }

    private suspend fun draw(source: PlantUmlSource, request: PlantUmlRequest): PlantUmlResult {
        val started = timeSource.markNow()
        lock.withLock { running = started }
        try {
            var result = drawAt(source, request, request.scale)
            if (result == TOO_LARGE && request.scale > 1f) {
                log.d { "PlantUML drawing too large at scale ${request.scale}; drawing at scale 1" }
                result = drawAt(source, request, 1f)
            }
            logOutcome(source, result, started.elapsedNow().inWholeMilliseconds)
            lock.withLock { cache.put(request, result) }
            return result
        } finally {
            // Even an Error from the engine must not leave a failed drawing that every later request would join.
            withContext(NonCancellable) {
                lock.withLock {
                    running = null
                    drawings.remove(request)
                }
            }
        }
    }

    /** Blocks the [worker] thread: the engine cannot be suspended, and it already runs off the callers' threads. */
    private fun drawAt(source: PlantUmlSource, request: PlantUmlRequest, scale: Float): PlantUmlResult = try {
        engine.render(source, plantUmlPreamble(request.style, scale), limits)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "PlantUML engine failed type=${source.type}" }
        PlantUmlResult.Failed(PlantUmlFailure.Internal)
    }

    private fun logOutcome(source: PlantUmlSource, result: PlantUmlResult, millis: Long) {
        when (result) {
            is PlantUmlResult.Image -> log.d {
                "PlantUML drew type=${source.type} in $millis ms: ${result.png.size} bytes at scale ${result.scale}"
            }

            is PlantUmlResult.SyntaxError -> log.d {
                "PlantUML syntax error type=${source.type} line=${result.line ?: "unknown"}"
            }

            // Engine failures are logged with their cause where they happen.
            is PlantUmlResult.Failed -> log.d { "PlantUML drawing failed type=${source.type}: ${result.reason}" }

            PlantUmlResult.Unsupported -> log.d { "PlantUML type=${source.type} is not drawn in-process" }
        }
    }

    /** One drawing shared by identical requests: [started] completes once the worker picks it up. */
    private data class Drawing(val started: CompletableDeferred<Unit>, val result: Deferred<PlantUmlResult>)

    private companion object {
        val log = Log.tag("PlantUmlRenderer")
        val TOO_LARGE = PlantUmlResult.Failed(PlantUmlFailure.TooLarge)
    }
}
