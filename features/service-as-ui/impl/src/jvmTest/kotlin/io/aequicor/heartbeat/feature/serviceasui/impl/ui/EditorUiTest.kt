package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.PlacementUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.DiagnosticUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorPhase
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.SurfaceInfoUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class EditorUiTest {
    private val previousLocale = Locale.getDefault()
    private val state = EditorState(
        phase = EditorPhase.Ready,
        info = SurfaceInfoUi(
            title = "PR map",
            owner = "PR review",
            placement = PlacementUi.Panel,
            launchers = persistentListOf(PlacementUi.Composer, PlacementUi.Start),
            revision = 4,
            isEnabled = true,
        ),
        text = """{"version":1}""",
        diagnostics = persistentListOf(DiagnosticUi("$.body", "the structure does not match", isWarning = false)),
        isValid = false,
        isDirty = true,
    )

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `the editor shows the surface, its problems and deletes only after confirmation`() = runSkikoComposeUiTest(
        size = Size(560f, 1600f),
    ) {
        val intents = mutableListOf<EditorIntent>()
        setContent {
            HbTheme(darkTheme = false) { EditorContent(state, persistentMapOf(), { intents += it }, onBack = {}) }
        }

        onNodeWithText("PR review").assertIsDisplayed()
        onNodeWithText("Above the input, New chat").assertIsDisplayed()
        onNodeWithText("Error: $.body: the structure does not match").performScrollTo().assertIsDisplayed()
        onNodeWithTag("service-ui-editor-save").assertIsNotEnabled()
        onNodeWithText("The preview appears once the spec is valid").performScrollTo().assertIsDisplayed()

        onNodeWithTag("service-ui-editor-delete").performScrollTo().performClick()
        onNodeWithText("Delete “PR map”?").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        onNodeWithTag("service-ui-editor-delete").performClick()
        onNodeWithTag("service-ui-editor-delete-confirm").performClick()
        assertEquals(listOf<EditorIntent>(EditorIntent.Delete), intents)
    }

    @Test
    fun `a draft over a changed surface asks which one to keep`() = runSkikoComposeUiTest(size = Size(360f, 1400f)) {
        val intents = mutableListOf<EditorIntent>()
        setContent {
            HbTheme(darkTheme = true) {
                EditorContent(
                    state.copy(isValid = true, diagnostics = persistentListOf(), isChangedElsewhere = true),
                    persistentMapOf(),
                    { intents += it },
                    onBack = {},
                )
            }
        }

        onNodeWithTag("service-ui-editor-save").assertIsNotEnabled()
        onNodeWithText("The interface was changed meanwhile", substring = true).assertIsDisplayed()
        onNodeWithText("Use saved version").performClick()
        onNodeWithText("Keep my draft").performClick()
        assertEquals(listOf(EditorIntent.Revert, EditorIntent.KeepDraft), intents)
    }

    @Test
    fun `a missing surface explains itself`() = runSkikoComposeUiTest(size = Size(480f, 400f)) {
        setContent {
            HbTheme(darkTheme = false) {
                EditorContent(EditorState(EditorPhase.Missing), persistentMapOf(), {}, onBack = {})
            }
        }
        onNodeWithText("This interface no longer exists").assertIsDisplayed()
    }
}
