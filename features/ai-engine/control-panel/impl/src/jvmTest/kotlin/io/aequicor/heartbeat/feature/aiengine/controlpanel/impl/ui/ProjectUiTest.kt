package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFieldUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFileUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigPageUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigSectionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldEditorUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldInputUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ProjectFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ProjectIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ProjectItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ProjectTrustUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ProjectUiTest {
    private val overview = EngineControlPanelState(
        "pi",
        title = "Pi",
        projects = persistentListOf(ProjectItemUi("web", "Web", ProjectTrustUi.Trusted)),
        isLoading = false,
    )
    private val form = ConfigFormUi(
        persistentListOf(
            ConfigPageUi(
                "main",
                text("Main"),
                persistentListOf(
                    ConfigSectionUi(
                        "main",
                        text("Main"),
                        null,
                        persistentListOf(
                            ConfigFieldUi(
                                "retries",
                                text("Retries"),
                                null,
                                FieldEditorUi.Number("", default = 2, min = null, max = null, unit = null),
                                isSet = false,
                            ),
                        ),
                    ),
                ),
            ),
        ),
        isDirty = true,
        files = persistentListOf(ConfigFileUi("settings", text("Settings"), ".pi/settings.json", "{}", "json")),
    )
    private val project = ProjectFormUi("web", "Web", ProjectTrustUi.Content, form)

    @Test
    fun `a project opens from the overview`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        setContent { HbTheme { EngineControlPanelContent(overview, { intents += it }, {}) } }
        onNodeWithTag("engine-control-project:web").performClick()
        runOnIdle { assertEquals(listOf<EngineControlPanelIntent>(ProjectIntent.Open("web")), intents) }
    }

    @Test
    fun `a project edits its fields and files and saves them`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        val state = overview.copy(openProject = "web", project = project)
        setContent { HbTheme { EngineControlPanelContent(state, { intents += it }, {}) } }

        onNodeWithTag("project-trust").assertExists()
        onNodeWithTag("config-number:retries").performTextReplacement("5")
        onNodeWithTag("config-save").performClick()
        onNodeWithTag("config-discard").performClick()
        onNodeWithTag("project-file:settings").performClick()
        onNodeWithTag("control-back").performClick()
        runOnIdle {
            assertEquals(
                listOf<EngineControlPanelIntent>(
                    ProjectIntent.EditField("retries", FieldInputUi.Number("5")),
                    ProjectIntent.Save,
                    ProjectIntent.Reload,
                    ProjectIntent.OpenFile("settings"),
                    ProjectIntent.Close,
                ),
                intents,
            )
        }
    }

    @Test
    fun `back from a project file returns to the project`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        val state = overview.copy(openProject = "web", projectFile = "settings", project = project)
        setContent { HbTheme { EngineControlPanelContent(state, { intents += it }, {}) } }

        onNodeWithTag("config-file:settings").assertExists()
        onNodeWithTag("control-back").performClick()
        runOnIdle { assertEquals(listOf<EngineControlPanelIntent>(ProjectIntent.CloseFile), intents) }
    }

    private companion object {
        fun text(value: String) = TextUi(value, persistentMapOf())
    }
}
