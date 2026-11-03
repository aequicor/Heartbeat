package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpProgress
import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.aequicor.heartbeat.core.network.OfflineHttpSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.sse.DefaultClientSSESession
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.sse.SSEClientContent
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.isSseRequest
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Feeds raw fixture bytes to actual codecs. Production wire matching is checked independently in DI tests. */
internal class SdkProbeHttpFixture : OfflineHttpClients {
    private val dispatcher = Dispatchers.IO
    val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    val sessions = mutableListOf<OfflineHttpSession>()
    var isLeakingSdkJob = false
    var cleanupFailure: Exception? = null
    var isSdkCloseFailing = false
    val entered = CompletableDeferred<Unit>()
    val cleanupAttempts = AtomicInteger()

    @OptIn(InternalAPI::class)
    override suspend fun open(script: List<OfflineHttpExchange>): OfflineHttpSession {
        val exchange = script.single()
        val received = AtomicInteger()
        val engineJob = CompletableDeferred<Job>()
        val config = MockEngineConfig().apply {
            dispatcher = this@SdkProbeHttpFixture.dispatcher
            addHandler { request ->
                received.incrementAndGet()
                entered.complete(Unit)
                if (isLeakingSdkJob) Job(engineJob.await())
                val reply = exchange.response as? OfflineHttpResponse.Reply ?: awaitCancellation()
                // respond exposes the original call context; the handler's withContext job ends on return.
                val call = respond("").callContext
                val bytes = ByteReadChannel(reply.body.encodeToByteArray())

                // The pinned Ktor parser needs its deprecated three-argument constructor to take an owner.
                @Suppress("DEPRECATION")
                val body = if (request.isSseRequest()) {
                    DefaultClientSSESession(request.body as SSEClientContent, bytes, call + Job(engineJob.await()))
                } else {
                    bytes
                }
                HttpResponseData(
                    HttpStatusCode.fromValue(reply.status),
                    GMTDate(),
                    Headers.build { reply.headers.forEach { (key, value) -> append(key, value) } },
                    HttpProtocolVersion.HTTP_1_1,
                    body,
                    call,
                )
            }
        }
        val engine = object : MockEngine(config) {
            override val supportedCapabilities = super.supportedCapabilities + SSECapability
        }
        engineJob.complete(engine.coroutineContext.job)
        val clients = AtomicInteger()
        val closeTracker = createClientPlugin("SdkCloseFailure") {
            val isChild = clients.getAndIncrement() > 0
            onClose {
                if (isChild && isSdkCloseFailing) error("Synthetic SDK close failure")
            }
        }
        return object : OfflineHttpSession {
            override val client = HttpClient(engine) { install(closeTracker) }
            override suspend fun progress() = OfflineHttpProgress(1, received.get(), isMatched = received.get() <= 1)
            override fun close() {
                client.close()
                client.coroutineContext.job.cancel()
                engine.close()
                engine.coroutineContext.job.cancel()
            }
            override suspend fun closeAndAwait() {
                withContext(NonCancellable) {
                    close()
                    client.coroutineContext.job.join()
                    engine.coroutineContext.job.join()
                }
                cleanupAttempts.incrementAndGet()
                cleanupFailure?.let { throw it }
            }
        }.also(sessions::add)
    }
}
