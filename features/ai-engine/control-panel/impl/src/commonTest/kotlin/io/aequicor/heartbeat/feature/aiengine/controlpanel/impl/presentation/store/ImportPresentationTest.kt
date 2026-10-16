package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNote
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNoteReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportPresentationTest {
    private val owned = ImportNote("settings", ImportNoteReason.OwnedKeyDropped, listOf("defaultModel"))
    private val preview = ImportPreview(
        "~/.pi/agent",
        listOf(
            ImportItem("settings", LocalizedText("Settings"), count = 4, notes = listOf(owned)),
            ImportItem("agents", LocalizedText("AGENTS.md")),
        ),
    )

    @Test
    fun `a preview offers every part and keeps the choice while the parts stay the same`() {
        val first = preview.toUi(null)
        assertEquals(persistentSetOf("settings", "agents"), first.selected)
        assertEquals(
            persistentListOf(ImportNoteUi(ImportNoteReasonUi.OwnedKeyDropped, "defaultModel")),
            first.items.first().notes,
        )

        val chosen = first.copy(selected = persistentSetOf("agents"), isReplacing = true)
        assertEquals(chosen, preview.toUi(chosen))
        val other = ImportPreview("~/.pi/agent", listOf(ImportItem("mcp", LocalizedText("MCP"))))
        assertEquals(persistentSetOf("mcp"), other.toUi(chosen).selected)
    }

    @Test
    fun `a report names the imported parts by their titles`() {
        val report = ImportReport(listOf("settings", "skills"), listOf(owned)).toUi(preview.toUi(null))

        assertEquals(listOf("Settings", "skills"), report.imported.map { it.default })
        assertEquals(listOf(ImportNoteReasonUi.OwnedKeyDropped), report.notes.map { it.reason })
    }

    @Test
    fun `every facade note has a panel counterpart`() {
        ImportNoteReason.entries.forEach { ImportNoteReasonUi.valueOf(it.name) }
    }
}
