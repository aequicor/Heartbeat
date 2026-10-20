package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.scopeFor
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiVendorModelsTest {
    @Test
    fun `pinned vendor routes pin only the endpoint and never a model or a key`() {
        val expected = mapOf(
            PiProvider.ZaiCodingPlan to "https://api.z.ai/api/coding/paas/v4",
            PiProvider.QwenTokenPlan to "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
            PiProvider.QwenTokenPlanIndividual to
                "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
            PiProvider.QwenTokenPlanCn to "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
        )
        expected.forEach { (provider, baseUrl) ->
            val config = Json.parseToJsonElement(assertNotNull(piVendorModelsJson(provider))).jsonObject
            val providers = config.getValue("providers").jsonObject
            assertEquals(setOf(provider.id), providers.keys)
            val pinned = providers.getValue(provider.id).jsonObject
            assertEquals(baseUrl, pinned.getValue("baseUrl").jsonPrimitive.content)
            assertEquals("\$${provider.variable}", pinned.getValue("apiKey").jsonPrimitive.content)
            assertEquals(setOf("baseUrl", "apiKey"), pinned.keys)
        }
    }

    @Test
    fun `other routes keep their native or compatible configuration`() {
        PiProvider.entries.filter { it.pinnedBaseUrl == null }.forEach { provider ->
            assertNull(piVendorModelsJson(provider))
        }
    }

    @Test
    fun `saved coding plan scopes remain compatible with the preset`() {
        val scope = PiDescriptor.connectionMethods.single { it.id.value == "zai" }.scopeFor()
        assertEquals("zai", scope.provider.value)
        assertEquals("https://api.z.ai", scope.origin.value)
        assertNull(scope.basePath)
    }

    @Test
    fun `saved token plan scopes remain compatible with their presets`() {
        listOf("qwen-token-plan", "qwen-token-plan-individual", "qwen-token-plan-cn").forEach { id ->
            val scope = PiDescriptor.connectionMethods.single { it.id.value == id }.scopeFor()
            assertEquals(id, scope.provider.value)
            assertTrue(scope.origin.value.startsWith("https://token-plan."))
            assertNull(scope.basePath)
        }
    }
}
