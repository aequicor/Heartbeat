package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Registers SDK children before their constructor can fail; the acquisition already owns the cleanup scope. */
internal class OwnedKoogHttpFactory(
    private val delegate: KoogHttpClient.Factory,
    private val lease: OwnedKoogHttpLease,
    private val ollama: IsolatedOllamaCompletion? = null,
    private val ollamaStream: OllamaStreamTerminal? = null,
) : KoogHttpClient.Factory {
    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient {
        val client = delegate.create(
            clientName,
            baseUrl,
            headers,
            queryParameters,
            requestTimeoutMillis,
            connectTimeoutMillis,
            socketTimeoutMillis,
            json,
        )
        val ktor = client as? KtorKoogHttpClient
        if (ktor == null) {
            client.close()
            throw IsolatedKoogRefusal(IsolatedPromptFailure.IsolationUnproven)
        }
        lease.add(ktor.ktorClient)
        return when {
            ollama != null -> IsolatedOllamaHttp(client, ollama)
            ollamaStream != null -> StrictOllamaStream(client, ollamaStream)
            else -> client
        }
    }
}

/**
 * Created before SDK construction. Owns only the intermediate/SDK HTTP clients, never the injected application
 * client. Acquisition registers synchronously, and the host finally closes and joins all registered jobs even
 * when SDK construction throws or is cancelled before a KoogClient exists.
 */
internal class OwnedKoogHttpLease {
    private val log = Log.tag("KoogHttpLease")
    private val clients = mutableListOf<HttpClient>()

    fun add(client: HttpClient) {
        clients += client
    }

    /** Abort every owned client before joining: Ktor SSE acquisition can outlive a cancelled consumer. */
    fun close() {
        // Collect synchronous cleanup failures so one broken plugin cannot strand the remaining children.
        // Cancellation is retained and rethrown after all close attempts, with priority over other failures.
        val failures = clients.mapNotNull { client ->
            runCatching {
                try {
                    client.close()
                } finally {
                    client.coroutineContext.job.cancel()
                }
            }.exceptionOrNull()
        }
        rethrowKoogCleanupFailures(failures)
    }

    suspend fun closeAndAwait() {
        log.v { "Closing owned SDK HTTP leases: count=${clients.size}" }
        try {
            close()
        } finally {
            withContext(NonCancellable) { clients.forEach { it.coroutineContext.job.join() } }
        }
    }
}
