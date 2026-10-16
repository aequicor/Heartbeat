package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateEngineConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateEngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiConfigSchemaTest {
    private val fields = PiConfigSchema.pages.flatMap { page -> page.sections.flatMap { it.fields } }

    @Test
    fun `the Pi schema is consistent`() {
        assertEquals(emptyList(), validateEngineConfigSchema(PiConfigSchema))
    }

    @Test
    fun `every title has a Russian translation`() {
        val titles = PiConfigSchema.documents.map { it.title } + PiConfigSchema.pages.map { it.title } +
            PiConfigSchema.pages.flatMap { page -> page.sections.map { it.title } } + fields.map { it.title }
        assertTrue(titles.all { "ru" in it.byLanguage }, titles.filter { "ru" !in it.byLanguage }.toString())
    }

    @Test
    fun `heartbeat launch choices and agent directory settings are profile only`() {
        val profileOnly = fields.filter { it.scopes == setOf(ConfigScope.Profile) }.map { it.id }.toSet()
        assertTrue(fields.filter { it.binding.document == PiDocuments.Host }.all { it.id in profileOnly })
        assertTrue(setOf("cache_warming", "proxy").all { it in profileOnly })
        assertEquals(setOf(ConfigScope.Profile), PiConfigSchema.document(PiDocuments.Host)?.scopes)
    }

    @Test
    fun `keys Heartbeat sets itself are owned`() {
        val owned = PiConfigSchema.ownedKeys.map { it.path.single() }.toSet()
        assertEquals(
            setOf(
                "defaultProvider",
                "defaultModel",
                "enabledModels",
                "defaultThinkingLevel",
                "modelThinkingLevels",
                "sessionDir",
                "defaultTools",
                "defaultProjectTrust",
            ),
            owned,
        )
        val notes = fields.filter { it.kind is ConfigFieldKind.ManagedNote }
        assertTrue(notes.all { (it.binding as ConfigBinding.JsonPath).path.single() in owned })
    }

    @Test
    fun `a typical Pi configuration validates`() {
        val servers = Json.parseToJsonElement(
            """
            {
              "github": {"command": "npx", "args": ["-y", "server"], "env": {"MODE": "x"}, "exposure": "direct"},
              "docs": {"url": "https://mcp.example", "timeout": 30, "toolExposure": {"search*": "deferred"}}
            }
            """.trimIndent(),
        ).jsonObject
        val values = EngineConfigValues()
            .write(ConfigBinding.JsonPath(PiDocuments.Settings, listOf("retry", "maxRetries")), JsonPrimitive(5))
            .write(ConfigBinding.JsonPath(PiDocuments.Settings, listOf("transport")), JsonPrimitive("websocket"))
            .write(ConfigBinding.JsonPath(PiDocuments.Mcp, listOf("mcpServers")), servers)
            .write(PiHostKeys.Mcp, JsonPrimitive(true))
            .write(ConfigBinding.WholeDocument(PiDocuments.Agents), JsonPrimitive("Be brief"))

        assertEquals(emptyList(), validateEngineConfig(PiConfigSchema, values, ConfigScope.Profile))
        val project = validateEngineConfig(PiConfigSchema, values, ConfigScope.Project)
        assertEquals(setOf(ConfigProblemReason.NotInScope), project.map { it.reason }.toSet())
        assertEquals(setOf(PiDocuments.Host), project.map { it.document }.toSet())
    }
}
