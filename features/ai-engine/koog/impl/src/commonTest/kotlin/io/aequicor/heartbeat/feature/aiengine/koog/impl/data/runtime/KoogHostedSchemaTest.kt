package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolParameterType
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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

    @Test
    fun `a hosted tool runs under the call id the model assigned`() = runTest {
        var seen: AgentToolContext? = null
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun execute(
                context: AgentToolContext,
                name: String,
                arguments: JsonObject,
            ): AgentToolResult {
                seen = context
                return AgentToolResult("ok")
            }
        }
        val context = AgentToolContext(SessionRef(KoogEngineId, SessionSourceId("test"), "s"), null, TurnId("turn"))
        val spec = AgentToolSpec("edit_file", "Edit", JsonObject(mapOf("type" to JsonPrimitive("object"))))
        val tool = koogHostedTools(listOf(spec), tools, context).single()
        tool.run(JsonObject(emptyMap()), ToolCallId("call-1"))
        assertEquals(ToolCallId("call-1"), seen?.callId)
        tool.run(JsonObject(emptyMap()))
        assertNull(seen?.callId)
    }
}
