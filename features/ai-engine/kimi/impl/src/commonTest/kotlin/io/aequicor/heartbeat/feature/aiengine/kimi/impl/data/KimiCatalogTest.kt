package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KimiCatalogTest {
    @Test
    fun `native config options expose every model effort and image input`() {
        val metadata = Json.parseToJsonElement(
            """{"configOptions":[
                {"id":"model","category":"model","options":[
                    {"value":"kimi-code/kimi-for-coding","name":"K2.8 Preview"},
                    {"value":"kimi-code/k3","name":"K3"},
                    {"value":"kimi-code/k3.1-pro","name":"K3.1 Pro"}
                ]},
                {"id":"thinking","category":"thought_level","currentValue":"high","options":[
                    {"value":"low"},{"value":"high"},{"value":"max"}
                ]}
            ]}""",
        ).jsonObject
        val capabilities = Json.parseToJsonElement(
            """{"promptCapabilities":{"image":true,"embeddedContext":true}}""",
        ).jsonObject

        val catalog = kimiNativeCatalog(metadata, capabilities)
        val models = catalog.modelInfo(KimiEngine.Id, EngineBindingId("binding"))

        assertEquals(
            listOf("kimi-code/kimi-for-coding", "kimi-code/k3", "kimi-code/k3.1-pro"),
            models.map { it.target.model.value },
        )
        assertEquals(listOf("low", "high", "max"), models.first().reasoningEfforts)
        assertEquals("high", models.first().defaultReasoningEffort)
        assertTrue("image/png" in models.first().inputSupport.imageMediaTypes)
        assertTrue("text/markdown" in models.first().inputSupport.resourceMediaTypes)
    }

    @Test
    fun `legacy model id maps to native catalog id`() {
        assertEquals("kimi-code/kimi-for-coding", nativeKimiModel(ModelId("kimi-for-coding")))
        assertEquals("kimi-code/k3", nativeKimiModel(ModelId("kimi-code/k3")))
    }
}
