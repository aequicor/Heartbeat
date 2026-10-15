package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.impl.di.BundledUiAuthoringKnowledge
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiAuthoringDocument
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BundledUiSkillExamplesTest {
    @Test
    fun `packaged examples parse and satisfy the runtime schema and binding rules`() = runTest {
        val examples = BundledUiAuthoringKnowledge().read(UiAuthoringDocument.Examples)
        val blocks = Regex("(?s)```json\\s*\\n(.*?)\\n```").findAll(examples).toList()
        assertTrue(blocks.isNotEmpty(), "The skill must supply executable examples")
        blocks.forEachIndexed { index, block ->
            val value = Json.parseToJsonElement(block.groupValues[1]).jsonObject
            val document = value["spec"] ?: value
            val placement = value["placement"]?.jsonPrimitive?.content?.let { name ->
                UiPlacement.entries.single { it.name.equals(name, ignoreCase = true) }
            } ?: UiPlacement.Card
            val rules = UiSpecRules(
                UiSpecOrigin.Library,
                placement,
                methods = setOf("snapshot", "save_note", "summary", "inspect_file", "export_folder", "activity"),
                surfaces = setOf("journal"),
            )
            val result = checkUiSpec(document.toString(), rules)
            val valid = assertIs<UiSpecCheck.Valid>(result, "Example ${index + 1}: $result")
            assertTrue(valid.warnings.isEmpty(), "Example ${index + 1}: ${valid.warnings}")
        }
    }
}
