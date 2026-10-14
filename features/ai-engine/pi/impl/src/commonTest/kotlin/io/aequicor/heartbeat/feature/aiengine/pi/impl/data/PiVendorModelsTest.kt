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

class PiVendorModelsTest {
    @Test
    fun `coding plan pins requests without replacing native model metadata or persisting a key`() {
        val config = Json.parseToJsonElement(assertNotNull(piVendorModelsJson(PiProvider.ZaiCodingPlan))).jsonObject
        val providers = config.getValue("providers").jsonObject
        assertEquals(setOf("zai"), providers.keys)
        val zai = providers.getValue("zai").jsonObject
        assertEquals("https://api.z.ai/api/coding/paas/v4", zai.getValue("baseUrl").jsonPrimitive.content)
        assertEquals("\$ZAI_API_KEY", zai.getValue("apiKey").jsonPrimitive.content)
        assertEquals(setOf("baseUrl", "apiKey"), zai.keys)
    }

    @Test
    fun `other routes keep their native or compatible configuration`() {
        PiProvider.entries.filter { it != PiProvider.ZaiCodingPlan }.forEach { provider ->
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
}
