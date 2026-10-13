package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentBridge
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

private val log = Log.tag("DesktopDocumentCalls")

/** Answer to one call of a page; exactly one of its methods runs once, unless the page cancelled the call. */
internal interface DesktopDocumentReply {
    /** Resolves the page's promise with the JSON text [json]. */
    fun success(json: String)

    /** Rejects the page's promise with [message]. */
    fun failure(message: String)
}

/** One call of a page: a method name and its JSON arguments. */
internal data class DesktopDocumentCall(val method: String, val arguments: JsonElement) {
    override fun toString(): String = "DesktopDocumentCall(arguments=***)"
}

/**
 * Calls of one page into its document's [bridge], run in [scope] (the document view's lifetime) on [dispatcher], off
 * CEF's and Swing's UI threads. Each call is bounded before parsing ([BrowserDocumentLimits.CALL_BYTES],
 * [BrowserDocumentLimits.JSON_DEPTH]) and while running ([BrowserDocumentLimits.CALLS] at a time); results over
 * [BrowserDocumentLimits.RESULT_BYTES] are refused. Every call started here gets exactly one reply: its outcome, or a
 * failure when [scope] ends first; a call the page cancels ([cancel], [cancelAll] on reload or close) gets none, as the
 * message router requires. The page decides how often it calls, so refusals are logged only at trace level; arguments,
 * results, method names and messages are never logged.
 */
internal class DesktopDocumentCalls(
    private val bridge: BrowserDocumentBridge,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val pending = ConcurrentHashMap<Long, Job>()

    /** Starts call [id] with the page's [request] text, or rejects it at once; parsing runs on [dispatcher]. */
    @HighFrequency
    fun start(id: Long, request: String, reply: DesktopDocumentReply) {
        // UTF-8 never has fewer bytes than characters: an over-long text is refused before any copy of it.
        if (request.length > BrowserDocumentLimits.CALL_BYTES) {
            log.v { "Document call rejected: over the limit" }
            reply.failure(MALFORMED)
            return
        }
        if (pending.size >= BrowserDocumentLimits.CALLS) {
            log.v { "Document call rejected: ${BrowserDocumentLimits.CALLS} calls are pending" }
            reply.failure(BUSY)
            return
        }
        val job = scope.launch(dispatcher, CoroutineStart.LAZY) {
            val outcome = parseDesktopDocumentCall(request)?.let { run(it) } ?: BrowserBridgeResult.Failure(MALFORMED)
            if (pending.remove(id, coroutineContext.job)) outcome.deliver(reply)
        }
        pending[id] = job
        job.invokeOnCompletion { cause -> if (cause != null && pending.remove(id, job)) reply.failure(CLOSED) }
        job.start()
    }

    /** The page withdrew call [id]; it gets no reply. */
    fun cancel(id: Long) {
        pending.remove(id)?.cancel()
    }

    /** The page reloaded or closed: its calls get no reply. */
    fun cancelAll() {
        pending.keys.toList().forEach(::cancel)
    }

    private suspend fun run(call: DesktopDocumentCall): BrowserBridgeResult = try {
        bridge.call(call.method, call.arguments)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redactedFailure()) { "Document bridge failed" }
        BrowserBridgeResult.Failure(FAILED)
    }

    private fun BrowserBridgeResult.deliver(reply: DesktopDocumentReply) = when (this) {
        is BrowserBridgeResult.Value -> {
            val json = value.toString()
            if (json.encodeToByteArray().size > BrowserDocumentLimits.RESULT_BYTES) {
                log.v { "Document call result refused: over the limit" }
                reply.failure(TOO_LARGE)
            } else {
                reply.success(json)
            }
        }

        is BrowserBridgeResult.Failure -> reply.failure(message.take(BrowserDocumentLimits.ERROR_CHARS))
    }

    private companion object {
        const val MALFORMED = "The call is malformed or too large"
        const val BUSY = "Too many calls are pending"
        const val CLOSED = "The document closed"
        const val FAILED = "The call failed"
        const val TOO_LARGE = "The result is too large"
    }
}

/**
 * The call in a page's [request] text, `{"method": "<name>", "args": <any JSON>}`, or null when it is over the size or
 * nesting limits, not that object, or its method is blank or longer than [BrowserDocumentLimits.METHOD_CHARS].
 * Absent arguments are `null`; other keys are ignored.
 */
internal fun parseDesktopDocumentCall(request: String): DesktopDocumentCall? {
    if (request.encodeToByteArray().size > BrowserDocumentLimits.CALL_BYTES) return null
    if (jsonNesting(request) > BrowserDocumentLimits.JSON_DEPTH) return null
    // Text that is not JSON is the page's mistake, answered with a failure: the page decides how often it happens.
    val root = parsedJson(request).getOrNull() as? JsonObject ?: return null
    val method = (root["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (method.isNullOrBlank() || method.length > BrowserDocumentLimits.METHOD_CHARS) return null
    return DesktopDocumentCall(method, root["args"] ?: JsonNull)
}

/** kotlinx reports malformed input by throwing; the caller decides what a failure means for its input. */
private fun parsedJson(text: String): Result<JsonElement> = try {
    Result.success(Json.parseToJsonElement(text))
} catch (e: SerializationException) {
    Result.failure(e)
}

/**
 * Deepest nesting of arrays and objects in JSON [text], counted character by character: the kotlinx tree reader
 * recurses per level, so deeper input must be refused before it reaches it. Strings are skipped.
 */
private fun jsonNesting(text: String): Int {
    var depth = 0
    var deepest = 0
    var isInString = false
    var isEscaped = false
    for (char in text) {
        when {
            isEscaped -> isEscaped = false

            isInString -> {
                isEscaped = char == '\\'
                isInString = char != '"'
            }

            char == '"' -> isInString = true

            char == '[' || char == '{' -> deepest = maxOf(deepest, ++depth)

            char == ']' || char == '}' -> depth--
        }
    }
    return deepest
}
