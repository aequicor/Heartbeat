package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeError
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.chunkFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.decodeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.encodeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * One extension connected through a native-messaging host. The host introduces the extension origin in the first
 * frame; a connection from an origin outside [isOriginAllowed] is closed at once. Requests from the extension are
 * dispatched concurrently, each answered exactly once; the app calls the extension with [call] and correlates
 * answers by id. Everything the connection started ends with it: pending calls fail as unavailable and watches
 * launched in [scope] are cancelled.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class BridgeConnection(
    override val id: Long,
    private val transport: FrameTransport,
    private val dispatcher: BridgeDispatcher,
    private val isOriginAllowed: (String) -> Boolean,
    private val maxConcurrentRequests: Int = MAX_CONCURRENT_REQUESTS,
) : BridgeSession {
    private val log = Log.tag("BridgeConnection")
    private val clientState = MutableStateFlow<BridgeClient?>(null)
    private val incoming = MutableSharedFlow<BridgeIncomingEvent>(extraBufferCapacity = EVENT_BUFFER)
    private val pending = mutableMapOf<String, CompletableDeferred<BridgeFrame.Response>>()
    private val lock = Mutex()
    private val sendLock = Mutex()
    private var nextCall = 0L
    private var nextChunkSet = 0L
    private val inFlight = AtomicInt(0)
    private var connectionScope: CoroutineScope? = null
    private var connectedOrigin: String? = null

    override val origin: String get() = checkNotNull(connectedOrigin) { "Connection is not introduced yet" }

    override val client: StateFlow<BridgeClient?> = clientState.asStateFlow()

    override val events: SharedFlow<BridgeIncomingEvent> = incoming.asSharedFlow()

    override val scope: CoroutineScope get() = checkNotNull(connectionScope) { "Connection is not running" }

    override fun accept(client: BridgeClient) {
        val previous = clientState.getAndUpdate { client }
        log.i {
            "Bridge client ready id=$id browser=${client.browserBrand} extension=${client.extensionVersion} " +
                "capabilities=${client.capabilities.sorted()} replaced=${previous != null}"
        }
    }

    /**
     * Serves the connection until the peer closes it or the caller is cancelled. [onIntroduced] runs once the host
     * introduced an allowed origin, before any request is handled.
     */
    suspend fun run(onIntroduced: (BridgeConnection) -> Unit = {}) {
        val first = transport.receive()?.let(::decodeFrame) as? BridgeFrame.Host
        val introduced = first?.origin
        if (introduced == null || !isOriginAllowed(introduced)) {
            log.w { "Bridge connection from an unknown caller closed id=$id introduced=${introduced != null}" }
            return
        }
        connectedOrigin = introduced
        val job = SupervisorJob(currentCoroutineContext().job)
        val running = CoroutineScope(currentCoroutineContext() + job)
        connectionScope = running
        onIntroduced(this)
        try {
            while (true) {
                val payload = transport.receive() ?: break
                route(running, payload)
            }
        } finally {
            job.cancel()
            clientState.value = null
            withContext(NonCancellable) { failPending() }
            log.i { "Bridge connection ended id=$id" }
        }
    }

    override suspend fun emit(event: String, params: JsonObject) {
        send(BridgeFrame.Event(event, params))
    }

    override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
        val deferred = CompletableDeferred<BridgeFrame.Response>()
        val callId = lock.withLock {
            "a:${++nextCall}".also { pending[it] = deferred }
        }
        try {
            send(BridgeFrame.Request(callId, method, params))
            val response = withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: throw BridgeException(BridgeErrorCode.TIMEOUT, "Extension did not answer")
            response.error?.let { throw BridgeException(it.code, it.message) }
            return response.result ?: JsonObject(emptyMap())
        } finally {
            withContext(NonCancellable) { lock.withLock { pending.remove(callId) } }
        }
    }

    @HighFrequency
    private suspend fun route(running: CoroutineScope, payload: ByteArray) {
        when (val frame = decodeFrame(payload)) {
            is BridgeFrame.Request -> admit(running, frame)

            is BridgeFrame.Response -> lock.withLock { pending[frame.id] }?.complete(frame)
                ?: log.v { "Late bridge response id=${frame.id}" }

            is BridgeFrame.Event -> receiveEvent(frame)

            is BridgeFrame.Chunk, is BridgeFrame.Host -> log.v { "Ignored bridge frame from the extension" }

            null -> Unit
        }
    }

    private suspend fun admit(running: CoroutineScope, request: BridgeFrame.Request) {
        if (inFlight.incrementAndFetch() > maxConcurrentRequests) {
            inFlight.decrementAndFetch()
            log.w { "Bridge request rejected: too many in flight method=${request.method}" }
            reply(request, BridgeReply.Refused(BridgeErrorCode.BUSY, "Too many requests"))
            return
        }
        running.launch {
            try {
                reply(request, dispatcher.dispatch(BridgeCall(request.method, request.params, this@BridgeConnection)))
            } finally {
                inFlight.decrementAndFetch()
            }
        }
    }

    private suspend fun reply(request: BridgeFrame.Request, reply: BridgeReply) {
        val response = when (reply) {
            is BridgeReply.Ok -> BridgeFrame.Response(request.id, result = reply.result)
            is BridgeReply.Refused -> BridgeFrame.Response(request.id, error = BridgeError(reply.code, reply.message))
        }
        try {
            send(response)
        } catch (e: BridgeException) {
            log.w(e) { "Bridge response lost method=${request.method}" }
        }
    }

    @HighFrequency
    private fun receiveEvent(frame: BridgeFrame.Event) {
        if (frame.event == CAPABILITIES_CHANGED) updateCapabilities(frame.params)
        if (!incoming.tryEmit(BridgeIncomingEvent(frame.event, frame.params))) {
            log.w(IllegalStateException("Bridge event buffer overflow")) { "Bridge event dropped event=${frame.event}" }
        }
    }

    private fun updateCapabilities(params: JsonObject) {
        val capabilities = (params["capabilities"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: return
        clientState.update { it?.copy(capabilities = capabilities) }
        log.i { "Bridge client capabilities changed id=$id capabilities=${capabilities.sorted()}" }
    }

    @HighFrequency
    private suspend fun send(frame: BridgeFrame) {
        val payload = encodeFrame(frame)
        sendLock.withLock {
            if (payload.size <= BridgeProtocol.MAX_TO_BROWSER_BYTES) {
                transport.send(payload)
            } else {
                val chunks = chunkFrame("c:${++nextChunkSet}", payload)
                log.v { "Chunked a bridge frame bytes=${payload.size} chunks=${chunks.size}" }
                chunks.forEach { transport.send(encodeFrame(it)) }
            }
        }
    }

    private suspend fun failPending() {
        val abandoned = lock.withLock { pending.values.toList().also { pending.clear() } }
        abandoned.forEach {
            it.completeExceptionally(BridgeException(BridgeErrorCode.UNAVAILABLE, "Extension disconnected"))
        }
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 32
        const val EVENT_BUFFER = 64
        const val CAPABILITIES_CHANGED = "capabilities.changed"
    }
}

/** Extracts a required string parameter. */
internal fun JsonObject.string(name: String): String =
    requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "Missing $name" }
