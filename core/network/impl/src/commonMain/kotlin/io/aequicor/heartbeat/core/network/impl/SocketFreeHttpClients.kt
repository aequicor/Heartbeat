package io.aequicor.heartbeat.core.network.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.core.network.OfflineHttpExchange
import io.aequicor.heartbeat.core.network.OfflineHttpSession
import io.aequicor.heartbeat.core.network.SensitiveNetworkRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

@ContributesBinding(AppScope::class)
@Inject
internal class SocketFreeHttpClients(private val dispatchers: DispatcherProvider) : OfflineHttpClients {
    private val log = Log.tag("OfflineHttpClients")

    override suspend fun open(script: List<OfflineHttpExchange>): OfflineHttpSession {
        val snapshot = offlineScript(script)
        currentCoroutineContext().ensureActive()
        val engine = ScriptedOfflineEngine(snapshot, dispatchers.io)
        var client: HttpClient? = null
        var isTransferred = false
        try {
            client = HttpClient(engine) {
                followRedirects = false
                install(HttpRequestRetry) { maxRetries = 0 }
                install(HttpTimeout) { requestTimeoutMillis = OFFLINE_REQUEST_TIMEOUT_MILLIS }
                install(ResponseHeaderValidation)
                install(OfflineFixturePrivacy)
                install(NetworkLogging)
            }
            val session = SocketFreeHttpSession(client, engine)
            log.d { "Created socket-free HTTP script: exchanges=${snapshot.size}" }
            currentCoroutineContext().ensureActive()
            isTransferred = true
            return session
        } finally {
            if (!isTransferred) {
                withContext(NonCancellable) {
                    try {
                        client?.close()
                    } finally {
                        client?.coroutineContext?.job?.cancel()
                        engine.close()
                        engine.coroutineContext.job.cancel()
                        client?.coroutineContext?.job?.join()
                        engine.coroutineContext.job.join()
                    }
                }
            }
        }
    }
}

private val OfflineFixturePrivacy = createClientPlugin("OfflineFixturePrivacy") {
    onRequest { request, _ -> request.attributes.put(SensitiveNetworkRequest, Unit) }
}

private class SocketFreeHttpSession(override val client: HttpClient, private val engine: ScriptedOfflineEngine) :
    OfflineHttpSession {
    override suspend fun progress() = engine.progress()

    override fun close() {
        // Complete all synchronous close attempts before propagating any error, prioritizing cancellation.
        val failures = try {
            listOfNotNull(
                runCatching { client.close() }.exceptionOrNull(),
                runCatching { engine.close() }.exceptionOrNull(),
            )
        } finally {
            client.coroutineContext.job.cancel()
            engine.coroutineContext.job.cancel()
        }
        val failure = failures.firstOrNull { it is CancellationException } ?: failures.firstOrNull() ?: return
        failures.filter { it !== failure }.forEach(failure::addSuppressed)
        throw failure
    }

    override suspend fun closeAndAwait() {
        try {
            close()
        } finally {
            withContext(NonCancellable) {
                client.coroutineContext.job.join()
                engine.coroutineContext.job.join()
            }
        }
    }
}

private const val OFFLINE_REQUEST_TIMEOUT_MILLIS = 30_000L
