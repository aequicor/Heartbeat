package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepSeekCatalogTest {
    @Test
    fun `parses nested model options from config options`() {
        val metadata = Json.parseToJsonElement(
            """
            {"configOptions":[{"id":"model","category":"model","options":[
              {"group":"deepseek-official","options":[{"value":"[\"deepseek-official\",\"deepseek-v4-flash\"]","name":"deepseek-v4-flash"}]},
              {"group":"deepseek-account","options":[
                {"value":"[\"deepseek-account\",\"deepseek-flash\"]","name":"DeepSeek-V4.1-Flash"},
                {"value":"[\"deepseek-account\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}
            ]}]}
            """.trimIndent(),
        ) as JsonObject
        val models = deepSeekNativeModels(metadata)
        assertEquals(3, models.size)
        assertEquals("""["deepseek-account","deepseek-flash"]""", models[1].value)
        assertEquals("DeepSeek-V4.1-Flash", models[1].title)
    }
}
