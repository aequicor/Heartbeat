package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekCatalogTest {
    @Test
    fun `parses nested model options from config options`() {
        val models = deepSeekNativeModels(Metadata)
        assertEquals(3, models.size)
        assertEquals("""["deepseek-account","deepseek-flash"]""", models[1].value)
        assertEquals("DeepSeek-V4.1-Flash", models[1].title)
    }

    @Test
    fun `initialize image capability becomes model input support`() {
        val catalog = deepSeekNativeCatalog(
            Metadata,
            json("""{"promptCapabilities":{"image":true,"embeddedContext":false}}"""),
            IMAGE_MODEL,
        )
        val models = catalog.modelInfo(EngineId("deepseek-harness"), EngineBindingId("binding"))

        assertEquals(3, models.size)
        assertTrue(catalog.inputSupport.accepts("image/png"))
        assertTrue(catalog.inputSupport.accepts("image/jpeg"))
        assertFalse(catalog.inputSupport.accepts("text/markdown"))
        assertTrue(models.single { it.target.model.value.contains("deepseek-flash") }.inputSupport.accepts("image/png"))
        assertFalse(models.single { it.target.model.value.contains("v4-flash") }.inputSupport.accepts("image/png"))
        assertFalse(models.single { it.target.model.value.contains("v4-pro") }.inputSupport.accepts("image/png"))
    }

    @Test
    fun `model id resolves from an opaque selector and a bare legacy id`() {
        assertEquals("deepseek-flash", dshSelectedModel("""["deepseek-account","deepseek-flash"]"""))
        assertEquals("deepseek-flash", dshSelectedModel("deepseek-flash"))
    }

    @Test
    fun `absent image capability leaves every model text-only`() {
        val catalog = deepSeekNativeCatalog(Metadata, json("""{"promptCapabilities":{"image":false}}"""), IMAGE_MODEL)

        assertTrue(catalog.inputSupport.imageMediaTypes.isEmpty())
        assertTrue(catalog.inputSupport.resourceMediaTypes.isEmpty())
        val models = catalog.modelInfo(EngineId("deepseek-harness"), EngineBindingId("binding"))
        assertTrue(models.all { model -> model.inputSupport.allowedMediaTypes.isEmpty() })
    }

    private companion object {
        const val IMAGE_MODEL = "deepseek-flash"

        val Metadata = json(
            """
            {"configOptions":[{"id":"model","category":"model","options":[
              {"group":"deepseek-official","options":[{"value":"[\"deepseek-official\",\"deepseek-v4-flash\"]","name":"deepseek-v4-flash"}]},
              {"group":"deepseek-account","options":[
                {"value":"[\"deepseek-account\",\"deepseek-flash\"]","name":"DeepSeek-V4.1-Flash"},
                {"value":"[\"deepseek-account\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}
            ]}]}
            """.trimIndent(),
        )

        fun json(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject
    }
}
