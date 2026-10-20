package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PiQwenCloudModelsTest {
    private val base = "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1"
    private val scope = AuthScope(
        CompatibleProtocol.OpenAI.provider.id,
        EndpointOrigin("https://token-plan.maas.qwencloudapi.com"),
        "/compatible-mode/v1",
    )

    @Test
    fun `id only QwenCloud discovery declares verified thinking and images without inventing capacity`() {
        val config = piModelsJson(
            PiProvider.OpenAiCompatible,
            scope,
            parseCompatibleModels("""{"data":[{"id":"qwen3.8-max"},{"id":"unverified"}]}"""),
        )
        val provider = Json.parseToJsonElement(config).jsonObject.getValue("providers").jsonObject
            .getValue("openai-compatible").jsonObject
        assertEquals(base, provider.getValue("baseUrl").jsonPrimitive.content)
        val models = provider.getValue("models").jsonArray.map { it.jsonObject }
        val known = models.first()
        assertEquals("true", known.getValue("reasoning").jsonPrimitive.content)
        assertEquals(listOf("text", "image"), known.getValue("input").jsonArray.map { it.jsonPrimitive.content })
        val levels = known.getValue("thinkingLevelMap").jsonObject
        assertEquals(setOf("low", "medium", "xhigh"), levels.filterValues { it != JsonNull }.keys)
        assertEquals("qwen", known.getValue("compat").jsonObject.getValue("thinkingFormat").jsonPrimitive.content)
        assertEquals(
            "true",
            known.getValue("compat").jsonObject.getValue("supportsReasoningEffort").jsonPrimitive.content,
        )
        assertEquals(
            "false",
            known.getValue("compat").jsonObject.getValue("supportsDeveloperRole").jsonPrimitive.content,
        )
        assertFalse("contextWindow" in known || "maxTokens" in known)
        assertEquals(emptyMap(), piConfiguredContextWindows(config))
        assertEquals(setOf("id"), models.last().keys)
    }

    @Test
    fun `QwenCloud defaults never cross host path protocol or model boundaries`() {
        assertNotNull(piQwenCloudModel("openai-completions", "$base/", "qwen3.8-max"))
        listOf(
            "https://other.example/compatible-mode/v1",
            "https://token-plan.maas.qwencloudapi.com/v1",
            "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1/proxy",
            "https://token-plan.maas.qwencloudapi.com.attacker.example/compatible-mode/v1",
            "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
        ).forEach { assertNull(piQwenCloudModel("openai-completions", it, "qwen3.8-max")) }
        assertNull(piQwenCloudModel("anthropic-messages", base, "qwen3.8-max"))
        assertNull(piQwenCloudModel("openai-completions", base, "qwen3.8-max-preview"))
        assertNull(piQwenCloudModel("openai-completions", base, "qwen3.8-flash"))
    }

    @Test
    fun `server declarations override documented input defaults and supply their own confirmed limit`() {
        val config = piModelsJson(
            PiProvider.OpenAiCompatible,
            scope,
            listOf(PiCompatibleModel("qwen3.8-max", inputs = listOf("text"), contextWindow = 50000)),
        )
        val model = Json.parseToJsonElement(config).jsonObject.getValue("providers").jsonObject
            .getValue("openai-compatible").jsonObject.getValue("models").jsonArray.single().jsonObject
        assertEquals(listOf("text"), model.getValue("input").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(mapOf("openai-compatible/qwen3.8-max" to 50000L), piConfiguredContextWindows(config))
    }
}
