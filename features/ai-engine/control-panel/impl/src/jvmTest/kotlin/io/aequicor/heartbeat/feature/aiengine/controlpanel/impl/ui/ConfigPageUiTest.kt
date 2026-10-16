package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFieldUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigPageUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigProblemReasonUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigProblemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigSectionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldEditorUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldInputUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.PageItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextRoleUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.UnitUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ConfigPageUiTest {
    private val form = ConfigFormUi(
        persistentListOf(
            ConfigPageUi(
                "network",
                text("Network"),
                persistentListOf(
                    ConfigSectionUi(
                        "main",
                        text("Main"),
                        null,
                        persistentListOf(
                            ConfigFieldUi("offline", text("Offline"), null, FieldEditorUi.Flag(null, false), false),
                            ConfigFieldUi("timeout", text("Timeout"), null, timeout, isSet = true),
                            ConfigFieldUi("skills", text("Skills"), null, skills, isSet = true),
                            ConfigFieldUi(
                                "model",
                                text("Model"),
                                null,
                                FieldEditorUi.Managed(text("Chosen in the chat")),
                                isSet = false,
                                problems = persistentListOf(
                                    ConfigProblemUi(ConfigProblemReasonUi.Owned, isBlocking = false),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        isDirty = true,
        blockingProblems = 1,
    )
    private val overview = EngineControlPanelState(
        "pi",
        title = "Pi",
        pages = persistentListOf(PageItemUi("network", text("Network"))),
        config = form,
        isLoading = false,
    )

    @Test
    fun `a page opens from the overview`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        setContent { HbTheme { EngineControlPanelContent(overview, { intents += it }, {}) } }

        onNodeWithTag("engine-control-page:network").performClick()
        runOnIdle { assertEquals(listOf<EngineControlPanelIntent>(ConfigIntent.OpenPage("network")), intents) }
    }

    @Test
    fun `fields of an open page send their edits and a draft with problems cannot be saved`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        setContent { HbTheme { EngineControlPanelContent(overview.copy(openPage = "network"), { intents += it }, {}) } }

        onNodeWithTag("config-flag:offline").performClick()
        onNodeWithTag("config-number:timeout").performTextReplacement("45")
        onNodeWithTag("config-reset:timeout").performClick()
        onNodeWithTag("config-item-add:skills").performClick()
        onNodeWithTag("config-item-remove:skills:0").performClick()
        onNodeWithTag("config-save").assertIsNotEnabled()
        onNodeWithTag("config-discard").performClick()
        onNodeWithTag("control-back").performClick()
        runOnIdle {
            assertEquals(
                listOf(
                    edit("offline", FieldInputUi.Flag(true)),
                    edit("timeout", FieldInputUi.Number("45")),
                    edit("timeout", FieldInputUi.Reset),
                    edit("skills", FieldInputUi.TextList(persistentListOf("a", ""))),
                    edit("skills", FieldInputUi.TextList(persistentListOf())),
                    ConfigIntent.DiscardConfig,
                    ConfigIntent.ClosePage,
                ),
                intents,
            )
        }
    }

    @Test
    fun `a conflict is resolved only by discarding the draft`() = runSkikoComposeUiTest {
        val intents = mutableListOf<EngineControlPanelIntent>()
        val conflict = overview.copy(openPage = "network", config = form.copy(hasConflict = true))
        setContent { HbTheme { EngineControlPanelContent(conflict, { intents += it }, {}) } }

        onNodeWithTag("config-conflict").assertExists()
        onNodeWithTag("config-save").assertDoesNotExist()
    }

    @Test
    fun `every configuration problem has a text`() {
        ConfigProblemReasonUi.entries.forEach { ConfigProblemTexts.getValue(it) }
    }

    private fun edit(field: String, input: FieldInputUi): EngineControlPanelIntent = ConfigIntent.EditField(
        field,
        input,
    )

    private companion object {
        val timeout = FieldEditorUi.Number("30", default = null, min = 0, max = null, unit = UnitUi.Seconds)
        val skills = FieldEditorUi.TextList(persistentListOf("a"), TextRoleUi.Path, isPatterns = true)

        fun text(value: String) = TextUi(value, persistentMapOf())
    }
}
