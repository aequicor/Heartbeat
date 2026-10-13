package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_stop
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit density and layout budgets, including empty data, narrow desktop and an active streamed reply. */
@OptIn(ExperimentalTestApi::class)
class AiStudioDesktopEdgeAuditUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useRussianLabels() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `narrow desktop retains readable prose and bounded composer in both themes`() {
        for (isDark in listOf(false, true)) {
            renderEdge(900, isDark, proseAuditWorkspace(), if (isDark) "900-dark-prose" else "900-light-prose")
        }
    }

    @Test
    fun `desktop supports empty history and collapsed sidebar`() {
        val empty = desktopAuditWorkspace(isEmpty = true).copy(
            sessions = persistentListOf(),
            projects = persistentListOf(),
        )
        renderEdge(1280, false, empty, "1280-light-no-chats", hasProse = false)
        val populated = proseAuditWorkspace()
        renderEdge(
            1280,
            false,
            populated.copy(sidebar = populated.sidebar.copy(isVisible = false)),
            "1280-light-collapsed",
        )
    }

    @Test
    fun `idle reply displays twenty five complete lines and streamed reply sends stop for its session`() {
        renderEdge(1280, false, proseAuditWorkspace(), "1280-light-prose")
        renderEdge(1280, false, proseAuditWorkspace(isStreaming = true), "1280-light-streaming")
    }
}

@OptIn(ExperimentalTestApi::class)
private fun renderEdge(
    width: Int,
    isDark: Boolean,
    state: AiStudioScreenState,
    name: String,
    hasProse: Boolean = true,
) = runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
    val events = mutableListOf<AiStudioScreenIntent>()
    var stopLabel = ""
    val isNewSession = state.panes.first().sessionId == null
    var contextRowAllowance = 0f
    setContent {
        stopLabel = stringResource(Res.string.composer_stop)
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            HbTheme(darkTheme = isDark) {
                contextRowAllowance = HbTheme.spacing.xs.value * 2 +
                    if (isNewSession) HbTheme.dimensions.composerPillHeight.value else 0f
                AiStudioContent(state, events::add, StudioExits(onBack = {}, onOpenToggles = {}))
            }
        }
    }
    settleAudit()
    assertDesktopSidebar(state)
    val lineCount = if (hasProse) visibleProseLines() else 0
    saveAudit(
        name,
        width,
        isDark,
        name.substringAfter("light-").substringAfter("dark-"),
        fixtureChats = state.sessions.size,
    )
    val metricsFile = File(desktopAuditDirectory(), "$name.json")
    val metrics = Json.parseToJsonElement(metricsFile.readText()).jsonObject
    metricsFile.writeText(JsonObject(metrics + ("visibleProseLines" to JsonPrimitive(lineCount))).toString())
    assertDesktopGeometry(width, state.running.isEmpty(), contextRowAllowance, isNewSession)
    assertDesktopProseLines(width, hasProse, state.running.isEmpty(), lineCount)
    if (state.running.isNotEmpty()) {
        onNodeWithContentDescription(stopLabel).assertIsDisplayed().performClick()
        assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.Stop("audit-0")), events)
    }
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.assertDesktopSidebar(state: AiStudioScreenState) {
    if (state.sidebar.isVisible) {
        onNodeWithTag("studio-sidebar").assertIsDisplayed()
        if (state.sessions.isNotEmpty()) {
            assertTrue(completeSessionRows() >= 18, "At least 18 complete chat rows must fit")
        }
    } else {
        onNodeWithTag("studio-sidebar").assertDoesNotExist()
        onNodeWithTag("pane-open-sidebar").assertIsDisplayed()
    }
}

private fun assertDesktopProseLines(width: Int, hasProse: Boolean, isIdle: Boolean, lineCount: Int) {
    if (hasProse && width >= 1280 && isIdle) {
        assertTrue(lineCount >= 25, "Expected at least 25 complete prose lines, found $lineCount")
    }
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.assertDesktopGeometry(
    width: Int,
    isIdle: Boolean,
    contextRowAllowance: Float,
    isNewSession: Boolean,
) {
    val header = onNodeWithTag("pane-header-0").fetchSemanticsNode().boundsInRoot
    val footer = onNodeWithTag("pane-footer-0").fetchSemanticsNode().boundsInRoot
    val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
    assertTrue(header.height in 40f..44f, "Desktop header height: ${header.height}")
    assertTrue(composer.height <= 96f + contextRowAllowance, "Desktop composer height: ${composer.height}")
    assertProjectContext(isNewSession)
    if (isIdle) {
        assertTrue(
            header.height + footer.height <= 160f + contextRowAllowance,
            "Header and footer leave at least ${640f - contextRowAllowance}px for conversation",
        )
    }
    assertTrue(composer.width <= 760f && composer.left >= 0f && composer.right <= width && composer.bottom <= 800f)
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.assertProjectContext(isNewSession: Boolean) {
    val project = onNodeWithTag("project-chip-0")
    if (!isNewSession) {
        project.assertDoesNotExist()
        return
    }
    val context = project.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val editor = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer-0")))
        .fetchSemanticsNode().boundsInRoot
    assertTrue(context.bottom <= editor.top, "Project context must remain above the editor")
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.completeSessionRows(): Int = onAllNodes(
    SemanticsMatcher("audit session rows") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("session-audit-") == true
    },
).fetchSemanticsNodes().count { it.boundsInRoot.height >= it.size.height - 0.5f }

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.visibleProseLines(): Int {
    val top = onNodeWithTag("pane-header-0").fetchSemanticsNode().boundsInRoot.bottom
    val bottom = onNodeWithTag("pane-footer-0").fetchSemanticsNode().boundsInRoot.top
    val matcher = hasAnyAncestor(hasTestTag("transcript-audit-0")) and SemanticsMatcher("audit prose text") {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("Строка аудита") } == true &&
            it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null
    }
    val nodes = onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
    return nodes.sumOf { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        layouts.sumOf { layout ->
            (0 until layout.lineCount).count { index ->
                val lineTop = node.positionInRoot.y + layout.getLineTop(index)
                val lineBottom = node.positionInRoot.y + layout.getLineBottom(index)
                val text = layout.layoutInput.text.text.substring(layout.getLineStart(index), layout.getLineEnd(index))
                text.isNotBlank() && lineTop >= maxOf(top, node.boundsInRoot.top) &&
                    lineBottom <= minOf(bottom, node.boundsInRoot.bottom)
            }
        }
    }
}

private fun proseAuditWorkspace(isStreaming: Boolean = false): AiStudioScreenState {
    val state = desktopAuditWorkspace(isEmpty = false)
    val prose = (1..60).joinToString(" ") { "Строка аудита $it: ясный текст ответа остаётся доступным для чтения." }
    return state.copy(
        running = if (isStreaming) persistentSetOf("audit-0") else persistentSetOf(),
        transcripts = persistentMapOf(
            "audit-0" to persistentListOf(
                MessageUi.Reply("audit-prose", state.now, prose, persistentListOf(), isStreaming),
            ),
        ),
    )
}
