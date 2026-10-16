package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigSection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.document
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigFilesTest {
    private val settings = ConfigDocumentId("settings")
    private val agents = ConfigDocumentId("agents")
    private val schema = EngineConfigSchema(
        documents = listOf(
            ConfigDocumentSpec(settings, text("Settings"), ConfigFormat.Json, ConfigScope.entries.toSet(), "s.json"),
            ConfigDocumentSpec(agents, text("Agents"), ConfigFormat.Text, ConfigScope.entries.toSet(), "AGENTS.md"),
            ConfigDocumentSpec(
                ConfigDocumentId("shared"),
                text("Shared"),
                ConfigFormat.Json,
                setOf(ConfigScope.Project),
                "p.json",
            ),
        ),
        pages = listOf(
            ConfigPage(
                "main",
                text("Main"),
                listOf(
                    ConfigSection(
                        "main",
                        text("Main"),
                        listOf(
                            ConfigField(
                                "retries",
                                text("Retries"),
                                ConfigBinding.JsonPath(settings, listOf("retries")),
                                ConfigFieldKind.Number(),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
    private val config = EngineConfigState(
        EngineId("pi"),
        schema,
        EngineConfigValues(
            mapOf(
                settings to ConfigDocument.Json(buildJsonObject { put("retries", 2) }),
                agents to ConfigDocument.Text("Be brief."),
            ),
        ),
        ConfigRevision(1),
    )

    @Test
    fun `the profile files show their documents`() {
        val files = config.form(null).files

        assertEquals(listOf("settings", "agents"), files.map { it.id })
        assertEquals("{\n    \"retries\": 2\n}", files[0].text)
        assertEquals("json", files[0].language)
        assertEquals("Be brief.", files[1].text)
    }

    @Test
    fun `a JSON file replaces its document while it parses and blocks saving while it does not`() {
        val parsed = config.edit(null, "retries", FieldInputUi.Number("2"))
            .let { config.editFile(it, "settings", "{\"retries\": 5, \"extra\": true}") }!!
        assertEquals(
            ConfigDocument.Json(
                buildJsonObject {
                    put("retries", 5)
                    put("extra", true)
                },
            ),
            parsed.values.document(settings),
        )

        val broken = config.editFile(parsed, "settings", "{\"retries\": ")!!
        assertEquals(parsed.values, broken.values)
        val form = config.form(broken)
        assertTrue(form.files.first { it.id == "settings" }.isInvalid)
        assertEquals(1, form.blockingProblems)
        assertEquals(broken, schema.settleFiles(broken))

        val fixed = config.editFile(broken, "settings", "{}")!!
        assertEquals(emptyMap(), schema.settleFiles(fixed).files)
    }

    @Test
    fun `a field edit shows its document anew and an empty file removes its document`() {
        val typed = config.editFile(null, "settings", "{\"retries\": 3}")
        assertEquals(emptyMap(), config.edit(typed, "retries", FieldInputUi.Number("4"))!!.files)

        val emptied = config.editFile(null, "agents", "")!!
        assertNull(emptied.values.document(agents))
        assertNull(config.editFile(null, "missing", "x"))
    }

    private companion object {
        fun text(value: String) = LocalizedText(value)
    }
}
