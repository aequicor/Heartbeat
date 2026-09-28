package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Model advertised by a compatible server. */
internal data class PiCompatibleModel(val id: String, val name: String?)

/**
 * Lists models of an OpenAI-/Anthropic-compatible server (`GET <base>/models`, Anthropic `<base>/v1/models`).
 * Pi requires custom providers to name their models, so the list is fetched before each process start.
 * Redirects are never followed with the key.
 */
@Inject
internal class PiCompatibleCatalog(private val httpClient: HttpClient) {
    private val log = Log.tag("PiCompatibleCatalog")

    suspend fun models(
        protocol: CompatibleProtocol,
        scope: AuthScope,
        key: String,
        source: AuthSourceId,
    ): List<PiCompatibleModel> {
        require(CompatibleProtocol.of(scope) == protocol) { "Scope not allowed" }
        val base = scope.origin.value + protocol.apiBase(scope)
        val url = when (protocol) {
            CompatibleProtocol.OpenAI -> "$base/models"
            CompatibleProtocol.Anthropic -> "$base/v1/models"
        }
        log.i { "Listing models of a ${protocol.name}-compatible server" }
        val client = httpClient.config { followRedirects = false }
        val text = try {
            client.use {
                val response = it.get(url) {
                    when (protocol) {
                        CompatibleProtocol.OpenAI -> header(HttpHeaders.Authorization, "Bearer $key")

                        CompatibleProtocol.Anthropic -> {
                            header("x-api-key", key)
                            header("anthropic-version", ANTHROPIC_VERSION)
                        }
                    }
                }
                checkStatus(response.status, source)
                response.bodyAsText()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            log.w(e) { "Compatible server is unreachable" }
            piFailure(EngineFailure.Transport(TransportFailureReason.NetworkUnavailable))
        } catch (e: IllegalArgumentException) {
            // Unresolvable hosts surface as UnresolvedAddressException, an IllegalArgumentException on the JVM.
            log.w(e) { "Compatible server is unreachable" }
            piFailure(EngineFailure.Transport(TransportFailureReason.NetworkUnavailable))
        }
        return try {
            parseCompatibleModels(text)
        } catch (e: SerializationException) {
            log.w(e) { "Compatible server returned a malformed model list" }
            piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Compatible server returned a malformed model list" }
            piFailure(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
        }
    }
}

private fun checkStatus(status: HttpStatusCode, source: AuthSourceId) {
    if (status.isSuccess()) return
    Log.tag("PiCompatibleCatalog").w { "Compatible server answered ${status.value}" }
    if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
        authenticationFailure(AuthFailureReason.NotAuthenticated, source)
    }
    piFailure(EngineFailure.Transport(TransportFailureReason.ServiceUnavailable))
}

/** Both protocols answer `{"data":[{"id":…}]}`; Anthropic adds `display_name`. */
internal fun parseCompatibleModels(text: String): List<PiCompatibleModel> {
    val data = Json.parseToJsonElement(text).jsonObject["data"] as? JsonArray
        ?: throw IllegalArgumentException("Missing data")
    return data.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val id = (model["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        PiCompatibleModel(id, (model["display_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    }.distinctBy { it.id }
}

/**
 * Pi `models.json` declaring [provider] at the API base of [scope]. The key is never written: Pi interpolates
 * [COMPATIBLE_KEY_VARIABLE] from the process environment.
 */
internal fun piModelsJson(provider: PiProvider, scope: AuthScope, models: List<PiCompatibleModel>): String {
    val protocol = requireNotNull(provider.compatible)
    val base = scope.origin.value + protocol.apiBase(scope)
    return buildJsonObject {
        put(
            "providers",
            buildJsonObject {
                put(
                    provider.id,
                    buildJsonObject {
                        when (protocol) {
                            CompatibleProtocol.OpenAI -> {
                                put("baseUrl", base)
                                put("api", "openai-completions")
                            }

                            CompatibleProtocol.Anthropic -> {
                                put("baseUrl", base)
                                put("api", "anthropic-messages")
                            }
                        }
                        put("apiKey", "\$$COMPATIBLE_KEY_VARIABLE")
                        put(
                            "models",
                            buildJsonArray {
                                models.forEach { model ->
                                    add(
                                        buildJsonObject {
                                            put("id", model.id)
                                            model.name?.let { put("name", it) }
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
    }.toString()
}

private const val ANTHROPIC_VERSION = "2023-06-01"
