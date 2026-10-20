package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PiCompatibleCatalogTest {
    private val origin = EndpointOrigin("https://llm.example")
    private val source = AuthSourceId("source")
    private val openAiScope = AuthScope(CompatibleProtocol.OpenAI.provider.id, origin)

    @Test
    fun `custom base paths replace the conventional prefix`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond("""{"data":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val catalog = PiCompatibleCatalog(HttpClient(engine))
        catalog.models(CompatibleProtocol.OpenAI, openAiScope.copy(basePath = "/api/v1"), "mock-key", source)
        val anthropic = AuthScope(CompatibleProtocol.Anthropic.provider.id, origin, "/anthropic/v1")
        catalog.models(CompatibleProtocol.Anthropic, anthropic, "mock-key", source)
        assertEquals(
            listOf("https://llm.example/api/v1/models", "https://llm.example/anthropic/v1/models"),
            urls,
        )
        val json = piModelsJson(PiProvider.AnthropicCompatible, anthropic, emptyList())
        assertTrue("\"baseUrl\":\"https://llm.example/anthropic\"" in json)
    }

    @Test
    fun `each protocol authenticates the models request its own way`() = runTest {
        CompatibleProtocol.entries.forEach { protocol ->
            val engine = MockEngine { request ->
                assertEquals("https://llm.example/v1/models", request.url.toString())
                when (protocol) {
                    CompatibleProtocol.OpenAI -> assertEquals(
                        "Bearer mock-key",
                        request.headers[HttpHeaders.Authorization],
                    )

                    CompatibleProtocol.Anthropic -> {
                        assertEquals("mock-key", request.headers["x-api-key"])
                        assertEquals("2023-06-01", request.headers["anthropic-version"])
                    }
                }
                respond(
                    """{"data":[{"id":"m1","display_name":"Model 1"},{"id":"m2"},{"id":""},{"id":"m1"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val models = PiCompatibleCatalog(
                HttpClient(engine),
            ).models(protocol, AuthScope(protocol.provider.id, origin), "mock-key", source)
            assertEquals(listOf(PiCompatibleModel("m1", "Model 1"), PiCompatibleModel("m2", null)), models)
        }
    }

    @Test
    fun `rejected key and malformed list map to engine failures`() = runTest {
        val denied = PiCompatibleCatalog(HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }))
        val auth = assertFailsWith<EngineException> {
            denied.models(CompatibleProtocol.OpenAI, openAiScope, "mock-key", source)
        }
        assertIs<EngineFailure.Authentication>(auth.failure)
        val malformed = PiCompatibleCatalog(HttpClient(MockEngine { respond("""{"models":[]}""") }))
        val protocol = assertFailsWith<EngineException> {
            malformed.models(CompatibleProtocol.OpenAI, openAiScope, "mock-key", source)
        }
        assertIs<EngineFailure.Transport>(protocol.failure)
    }

    @Test
    fun `models json points Pi at the server and keeps the key out of the file`() {
        val models = listOf(PiCompatibleModel("m1", "Model 1"))
        val openAi = Json.parseToJsonElement(piModelsJson(PiProvider.OpenAiCompatible, openAiScope, models))
            .jsonObject.getValue("providers").jsonObject.getValue("openai-compatible").jsonObject
        assertEquals("https://llm.example/v1", openAi.getValue("baseUrl").jsonPrimitive.content)
        assertEquals("openai-completions", openAi.getValue("api").jsonPrimitive.content)
        assertEquals("\$HEARTBEAT_PROVIDER_API_KEY", openAi.getValue("apiKey").jsonPrimitive.content)
        assertEquals("m1", openAi.getValue("models").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val anthropic = piModelsJson(
            PiProvider.AnthropicCompatible,
            AuthScope(CompatibleProtocol.Anthropic.provider.id, origin),
            models,
        )
        assertFalse("mock-key" in anthropic)
        val provider = Json.parseToJsonElement(anthropic)
            .jsonObject.getValue("providers").jsonObject.getValue("anthropic-compatible").jsonObject
        assertEquals("https://llm.example", provider.getValue("baseUrl").jsonPrimitive.content)
        assertEquals("anthropic-messages", provider.getValue("api").jsonPrimitive.content)
    }

    @Test
    fun `compatible models inherit thinking metadata of Pi catalog models at the same API base`() {
        val qwen = Json.parseToJsonElement(
            """{"id":"qwen3.8-max","name":"Qwen3.8 Max","api":"openai-completions","provider":"qwen-token-plan",
            "baseUrl":"https://llm.example/v1/","reasoning":true,"compat":{"thinkingFormat":"qwen"},
            "thinkingLevelMap":{"off":null,"low":"low"},"contextWindow":1000000,"cost":{"input":1}}""",
        ).jsonObject
        val elsewhere = Json.parseToJsonElement(
            """{"id":"m2","api":"openai-completions","baseUrl":"https://other.example/v1","reasoning":true}""",
        ).jsonObject
        val anthropic = Json.parseToJsonElement(
            """{"id":"m2","api":"anthropic-messages","baseUrl":"https://llm.example/v1","reasoning":true}""",
        ).jsonObject
        val catalog = listOf(qwen, elsewhere, anthropic)
        val known = piBuiltinModelsAt(catalog, "openai-completions", "https://llm.example/v1")
        assertEquals(setOf("qwen3.8-max"), known.keys)

        val json = piModelsJson(
            PiProvider.OpenAiCompatible,
            openAiScope,
            listOf(PiCompatibleModel("qwen3.8-max", null), PiCompatibleModel("m2", null)),
            known,
        )
        val models = Json.parseToJsonElement(json).jsonObject.getValue("providers").jsonObject
            .getValue("openai-compatible").jsonObject.getValue("models").jsonArray.map { it.jsonObject }
        val inherited = models.first()
        assertEquals("qwen", inherited.getValue("compat").jsonObject.getValue("thinkingFormat").jsonPrimitive.content)
        assertEquals("true", inherited.getValue("reasoning").jsonPrimitive.content)
        assertFalse("cost" in inherited || "provider" in inherited || "name" in inherited)
        assertEquals(setOf("id"), models.last().keys)
    }

    @Test
    fun `only explicit catalog capacities survive models json for the exact compatible route`() {
        val known = mapOf(
            "glm-5.3-flash" to Json.parseToJsonElement("""{"contextWindow":200000}""").jsonObject,
            "explicit-128k" to Json.parseToJsonElement("""{"contextWindow":128000}""").jsonObject,
            "invalid" to Json.parseToJsonElement("""{"contextWindow":0}""").jsonObject,
        )
        val config = piModelsJson(
            PiProvider.OpenAiCompatible,
            openAiScope,
            (known.keys + "unknown").map { PiCompatibleModel(it, null) },
            known,
        )
        assertEquals(
            mapOf("openai-compatible/glm-5.3-flash" to 200000L, "openai-compatible/explicit-128k" to 128000L),
            piConfiguredContextWindows(config),
        )
        assertEquals(emptyMap(), piConfiguredContextWindows(null))
    }

    @Test
    fun `capacities and modalities a server declares become part of the model`() {
        val models = parseCompatibleModels(
            """{"data":[
            {"id":"gpt-x","context_window":400000,"max_completion_tokens":128000,
            "architecture":{"input_modalities":["text","image"]}},
            {"id":"vllm-y","max_model_len":32768},
            {"id":"ollama-z","context_length":20480,"architecture":{"input_modalities":["text"]}},
            {"id":"junk","context_window":0,"max_tokens":-5,"architecture":{"input_modalities":["text","audio"]}},
            {"id":"plain","architecture":{"input_modalities":[]}}]}""",
        )
        assertEquals(
            listOf(
                PiCompatibleModel("gpt-x", null, 400000, 128000, listOf("text", "image")),
                PiCompatibleModel("vllm-y", null, 32768),
                PiCompatibleModel("ollama-z", null, 20480, null, listOf("text")),
                PiCompatibleModel("junk"),
                PiCompatibleModel("plain"),
            ),
            models,
        )
    }

    @Test
    fun `models json writes the declared fields and Pi catalog keeps the last word`() {
        val builtin = mapOf("m1" to Json.parseToJsonElement("""{"contextWindow":1000000}""").jsonObject)
        val declared = listOf(
            PiCompatibleModel("m1", null, 4096, 512, listOf("text", "image")),
            PiCompatibleModel("m2", null, 32768),
        )
        val models = Json.parseToJsonElement(piModelsJson(PiProvider.OpenAiCompatible, openAiScope, declared, builtin))
            .jsonObject.getValue("providers").jsonObject.getValue("openai-compatible").jsonObject
            .getValue("models").jsonArray.map { it.jsonObject }
        assertEquals(1000000L, models.first().getValue("contextWindow").jsonPrimitive.long)
        assertEquals(512L, models.first().getValue("maxTokens").jsonPrimitive.long)
        val inputs = models.first().getValue("input").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("text", "image"), inputs)
        assertEquals(setOf("id", "contextWindow"), models.last().keys)
        assertEquals(32768L, models.last().getValue("contextWindow").jsonPrimitive.long)
        assertEquals(
            mapOf("openai-compatible/m1" to 1000000L, "openai-compatible/m2" to 32768L),
            piConfiguredContextWindows(piModelsJson(PiProvider.OpenAiCompatible, openAiScope, declared, builtin)),
        )
    }

    @Test
    fun `discovery carries what the server listed about each model`() = runTest {
        val engine = MockEngine {
            respond(
                """{"data":[{"id":"m1","context_window":131072,
                "architecture":{"input_modalities":["text","image"]}}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val catalog = PiCompatibleCatalog(HttpClient(engine))
        val models = catalog.models(CompatibleProtocol.OpenAI, openAiScope, "mock-key", source)
        assertEquals(listOf(PiCompatibleModel("m1", null, 131072, null, listOf("text", "image"))), models)
    }

    @Test
    fun `catalog probe unlocks Pi providers with a placeholder key only`() {
        val providers = Json.parseToJsonElement(piCatalogProbeJson()).jsonObject.getValue("providers").jsonObject
        assertEquals(PiCatalogProviders.toSet(), providers.keys)
        assertTrue(providers.values.all { it.jsonObject.keys == setOf("apiKey") })
    }
}
