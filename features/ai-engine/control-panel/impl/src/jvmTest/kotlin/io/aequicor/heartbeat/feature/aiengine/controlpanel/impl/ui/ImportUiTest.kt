package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportNoteReasonUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportNoteUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportReportUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ImportUiTest {
    private val overview = EngineControlPanelState("pi", title = "Pi", canImport = true, isLoading = false)
    private val importing = ImportUi(
        "~/.pi/agent",
        persistentListOf(
            ImportItemUi(
                "settings",
                text("Settings"),
                4,
                persistentListOf(ImportNoteUi(ImportNoteReasonUi.OwnedKeyDropped, "defaultModel")),
            ),
            ImportItemUi("agents", text("AGENTS.md"), null, persistentListOf()),
        ),
        selected = persistentSetOf("settings", "agents"),
    )

    @Test
    fun `an import is chosen in its preview and confirmed`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        setContent {
            HbTheme {
                EngineControlPanelContent(
                    overview.copy(importing = importing),
                    { intents += it },
                    {},
                )
            }
        }

        onNodeWithTag("import-item:agents").performClick()
        onNodeWithTag("import-replace").performClick()
        onNodeWithTag("import-confirm").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineControlPanelIntent>(
                    ImportIntent.Toggle("agents"),
                    ImportIntent.SetReplacing(true),
                    ImportIntent.Import,
                ),
                intents,
            )
        }
    }

    @Test
    fun `unsaved changes keep an import from running`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        val dirty = overview.copy(importing = importing, config = ConfigFormUi(persistentListOf(), isDirty = true))
        setContent { HbTheme { EngineControlPanelContent(dirty, { intents += it }, {}) } }
        onNodeWithTag("import-confirm").assertIsNotEnabled()
    }

    @Test
    fun `the overview previews an import`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        setContent { HbTheme { EngineControlPanelContent(overview, { intents += it }, {}) } }

        onNodeWithTag("import-open").performClick()
        runOnIdle { assertEquals(listOf<EngineControlPanelIntent>(ImportIntent.Preview), intents) }
    }

    @Test
    fun `a finished import reports what it brought and closes`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        val report = ImportReportUi(persistentListOf(text("Settings")), persistentListOf())
        setContent {
            HbTheme {
                EngineControlPanelContent(
                    overview.copy(importReport = report),
                    { intents += it },
                    {},
                )
            }
        }

        onNodeWithTag("import-report").assertExists()
        onNodeWithTag("import-close").performClick()
        runOnIdle { assertEquals(listOf<EngineControlPanelIntent>(ImportIntent.Dismiss), intents) }
    }

    private companion object {
        fun text(value: String) = TextUi(value, persistentMapOf())
    }
}
