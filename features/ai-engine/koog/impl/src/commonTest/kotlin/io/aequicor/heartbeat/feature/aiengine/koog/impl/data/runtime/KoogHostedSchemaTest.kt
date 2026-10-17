package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolParameterType
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KoogHostedSchemaTest {
    @Test
    fun `build recipe schemas retain nested steps and environment dictionaries`() {
        val schema = Json.parseToJsonElement(
            """{
            "type":"object","required":["recipe"],"properties":{
                "recipe":{"type":"object","required":["steps"],"additionalProperties":false,"properties":{
                    "steps":{"type":"array","items":{"type":"object","required":["executable"],"properties":{
                        "executable":{"type":"string"},"arguments":{"type":"array","items":{"type":"string"}},
                        "environment":{"type":"object","additionalProperties":{"type":"string"}}
                    }}}
                }}
            }}""",
        ).jsonObject
        val tool = AgentToolSpec("configure_build", "Configure recipe", schema).koogDescriptor()
        val recipe = assertIs<ToolParameterType.Object>(tool.requiredParameters.single().type)
        assertEquals(listOf("steps"), recipe.requiredProperties)
        val steps = assertIs<ToolParameterType.List>(recipe.properties.single().type)
        val step = assertIs<ToolParameterType.Object>(steps.itemsType)
        assertEquals(listOf("executable"), step.requiredProperties)
        val environment = assertIs<ToolParameterType.Object>(step.properties.single { it.name == "environment" }.type)
        assertEquals(ToolParameterType.String, environment.additionalPropertiesType)
    }

    @Test
    fun `oneOf variants with const discriminators are declared as alternatives`() {
        val schema = Json.parseToJsonElement(
            """{
            "type":"object","required":["action"],"properties":{
                "action":{"oneOf":[
                    {"type":"object","required":["kind"],"properties":{"kind":{"const":"command"}}},
                    {"type":"object","required":["kind"],"properties":{"kind":{"const":"agent"}}}
                ]}
            }}""",
        ).jsonObject
        val tool = AgentToolSpec("scheduler_create_graph", "Create graph", schema).koogDescriptor()
        val action = assertIs<ToolParameterType.AnyOf>(tool.requiredParameters.single().type)
        val kinds = action.types.map { variant ->
            val variantObject = assertIs<ToolParameterType.Object>(variant.type)
            val kind = assertIs<ToolParameterType.Enum>(variantObject.properties.single().type)
            kind.entries.single()
        }
        assertEquals(listOf("command", "agent"), kinds)
    }

    @Test
    fun `typeless free-form schema is declared as an open object`() {
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"data":{"description":"Optional sample JSON"}}}""",
        ).jsonObject
        val tool = AgentToolSpec("ui_preview", "Preview", schema).koogDescriptor()
        val data = assertIs<ToolParameterType.Object>(tool.optionalParameters.single().type)
        assertEquals(emptyList(), data.properties)
    }
}
