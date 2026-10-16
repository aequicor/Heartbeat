package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class PiCompatibleStartupTest {
    private val source = AuthSourceId("source")
    private val scope = AuthScope(
        CompatibleProtocol.OpenAI.provider.id,
        EndpointOrigin("https://llm.example"),
        "/api/coding/paas/v4",
    )
    private val selected = ModelId("openai-compatible/glm-5.3")

    @Test
    fun `unavailable catalog declares the selected model while discovery still fails`() = runTest {
        val statuses = listOf(
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.TooManyRequests,
        )
        for (status in statuses) {
            val engine = MockEngine { request ->
                assertEquals("/api/coding/paas/v4/models", request.url.encodedPath)
                respond("private upstream response", status)
            }
            HttpClient(engine) { expectSuccess = true }.use { client ->
                val catalog = PiCompatibleCatalog(client)
                val models = catalog.models(CompatibleProtocol.OpenAI, scope, "mock-key", source, selected)
                assertEquals(listOf(PiCompatibleModel("glm-5.3", null)), models)
                val failure = assertFailsWith<EngineException> {
                    catalog.models(CompatibleProtocol.OpenAI, scope, "mock-key", source)
                }
                assertEquals(EngineFailure.Transport(TransportFailureReason.ServiceUnavailable), failure.failure)
                assertNull(failure.cause)

                val known = mapOf(
                    "glm-5.3" to Json.parseToJsonElement("""{"reasoning":true,"contextWindow":200000}""").jsonObject,
                )
                val config = piModelsJson(PiProvider.OpenAiCompatible, scope, models, known)
                val provider = Json.parseToJsonElement(config).jsonObject.getValue("providers").jsonObject
                    .getValue("openai-compatible").jsonObject
                assertEquals(
                    "https://llm.example/api/coding/paas/v4",
                    provider.getValue("baseUrl").jsonPrimitive.content,
                )
                val model = provider.getValue("models").jsonArray.single().jsonObject
                assertEquals("glm-5.3", model.getValue("id").jsonPrimitive.content)
                assertEquals("true", model.getValue("reasoning").jsonPrimitive.content)
                assertEquals(mapOf("openai-compatible/glm-5.3" to 200000L), piConfiguredContextWindows(config))
            }
        }
    }

    @Test
    fun `network and timeout failures can use the selected model for either protocol`() = runTest {
        for (protocol in CompatibleProtocol.entries) {
            for (error in listOf(IOException("offline"), SocketTimeoutException("timeout"))) {
                HttpClient(MockEngine { throw error }).use { client ->
                    val route = scope.copy(provider = protocol.provider.id)
                    val models = PiCompatibleCatalog(client).models(
                        protocol,
                        route,
                        "mock-key",
                        source,
                        ModelId("${protocol.provider.id.value}/selected/model"),
                    )
                    assertEquals(listOf(PiCompatibleModel("selected/model", null)), models)
                }
            }
        }
    }

    @Test
    fun `authentication redirects and invalid responses never fall back to the selected model`() = runTest {
        val statuses = listOf(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.Found,
            HttpStatusCode.BadRequest,
        )
        for (status in statuses) {
            var requests = 0
            val engine = MockEngine {
                requests++
                respond("private error", status, headersOf(HttpHeaders.Location, "https://elsewhere.example/models"))
            }
            HttpClient(engine) { expectSuccess = true }.use { client ->
                val failure = assertFailsWith<EngineException> {
                    PiCompatibleCatalog(client).models(CompatibleProtocol.OpenAI, scope, "mock-key", source, selected)
                }
                if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
                    assertIs<EngineFailure.Authentication>(failure.failure)
                } else {
                    assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), failure.failure)
                }
                assertEquals(1, requests)
            }
        }
        HttpClient(MockEngine { respond("""{"models":[]}""") }).use { client ->
            val failure = assertFailsWith<EngineException> {
                PiCompatibleCatalog(client).models(CompatibleProtocol.OpenAI, scope, "mock-key", source, selected)
            }
            assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), failure.failure)
        }
    }

    @Test
    fun `successful discovery remains authoritative and cancellation propagates`() = runTest {
        HttpClient(MockEngine { respond("""{"data":[{"id":"different"}]}""") }).use { client ->
            assertEquals(
                listOf(PiCompatibleModel("different", null)),
                PiCompatibleCatalog(client).models(CompatibleProtocol.OpenAI, scope, "mock-key", source, selected),
            )
        }
        val cancellation = CancellationException("cancelled startup")
        HttpClient(MockEngine { throw cancellation }).use { client ->
            val failure = assertFailsWith<CancellationException> {
                PiCompatibleCatalog(client).models(CompatibleProtocol.OpenAI, scope, "mock-key", source, selected)
            }
            assertEquals(cancellation.message, failure.message)
        }
    }

    @Test
    fun `foreign provider and empty model are refused before requesting the catalog`() = runTest {
        HttpClient(MockEngine { error("Invalid model must not reach the server") }).use { client ->
            for (model in listOf("anthropic/foreign", "openai-compatible/")) {
                val failure = assertFailsWith<EngineException> {
                    PiCompatibleCatalog(client).models(
                        CompatibleProtocol.OpenAI,
                        scope,
                        "mock-key",
                        source,
                        ModelId(model),
                    )
                }
                assertIs<EngineFailure.Access>(failure.failure)
            }
        }
    }
}
