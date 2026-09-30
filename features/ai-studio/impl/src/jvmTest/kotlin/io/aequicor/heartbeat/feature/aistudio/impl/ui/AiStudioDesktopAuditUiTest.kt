package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.reduce
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraft
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.jump_latest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Repeatable desktop baseline: actual screen, fixed density, 50 chats and no production behavior changes. */
@OptIn(ExperimentalTestApi::class)
class AiStudioDesktopAuditUiTest {
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
    fun `desktop audit preserves empty and long conversations across widths and themes`() {
        for (width in listOf(1000, 1280, 1600)) {
            for (isDark in listOf(false, true)) {
                for (isEmpty in listOf(true, false)) renderMatrix(width, isDark, isEmpty)
            }
        }
    }

    @Test
    fun `desktop audit records selected hover tab and search focus states`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            var state by mutableStateOf(desktopAuditWorkspace(isEmpty = false))
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(darkTheme = false) {
                        AiStudioContent(state, { state = state.auditIntent(it) }, auditExits)
                    }
                }
            }
            settleAudit()
            onNodeWithTag("session-audit-1").performClick()
            val selected = onNodeWithTag("session-audit-0")
            selected.performClick()
            selected.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            saveAudit("1280-light-selected", 1280, false, "selected")

            onNodeWithTag("sidebar-section-pinned").performClick()
            selected.assertDoesNotExist()
            saveAudit("1280-light-section-collapsed", 1280, false, "section-collapsed")
            onNodeWithTag("sidebar-section-pinned").performClick()
            selected.assertIsDisplayed()

            selected.performMouseInput { enter(center) }
            settleAudit()
            onNodeWithTag("session-menu-pinned:audit-0", useUnmergedTree = true).assertIsDisplayed()
            saveAudit("1280-light-hover", 1280, false, "hover")

            selected.performSemanticsAction(SemanticsActions.RequestFocus)
            selected.performKeyInput { pressKey(Key.Tab) }
            settleAudit()
            saveAudit("1280-light-tab", 1280, false, "tab")
            assertTrue(focusedAuditNodes().isNotEmpty(), "Tab must leave a visible focus target")

            onNodeWithTag("sidebar-search-open").performClick()
            settleAudit()
            onNodeWithTag("sidebar-search").assertIsFocused()
            saveAudit("1280-light-search-focus", 1280, false, "search-focus")
        }

    private fun renderMatrix(width: Int, isDark: Boolean, isEmpty: Boolean) =
        runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
            val state = desktopAuditWorkspace(isEmpty)
            var jumpLabel = ""
            setContent {
                jumpLabel = stringResource(Res.string.jump_latest)
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(darkTheme = isDark) { AiStudioContent(state, {}, auditExits) }
                }
            }
            settleAudit()
            if (!isEmpty) revealLatestAuditAnswer(jumpLabel)
            val mode = if (isEmpty) "empty" else "long"
            val theme = if (isDark) "dark" else "light"
            saveAudit("$width-$theme-$mode", width, isDark, mode)
            onNodeWithTag("composer-0").assertIsDisplayed()
            onNodeWithTag("studio-sidebar").assertIsDisplayed()
            val composer = onNodeWithTag("composer-0").fetchSemanticsNode().boundsInRoot
            assertTrue(composer.left >= 0f && composer.right <= width && composer.bottom <= 800f)
            assertEquals(50, state.sessions.size)
        }
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.revealLatestAuditAnswer(jumpLabel: String) {
    val transcript = onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("transcript-audit-0")))
    transcript.performScrollToNode(hasText("Проверка сборки — шаг 6"))
    onNodeWithText("Проверка сборки — шаг 6").performClick()
    transcript.performScrollToNode(hasTestTag("message-footer:audit-answer-5"))
    settleAudit()
    if (onAllNodesWithText(jumpLabel).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText(jumpLabel).performClick()
        settleAudit()
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.settleAudit() {
    mainClock.advanceTimeBy(1000)
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.saveAudit(
    name: String,
    width: Int,
    isDark: Boolean,
    state: String,
    fixtureChats: Int = 50,
) {
    val directory = desktopAuditDirectory()
    check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png")))
    val rows = onAllNodes(
        SemanticsMatcher("audit session rows") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("session-audit-") == true
        },
    ).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0f }
    val rowHeight = rows.maxOfOrNull { it.boundsInRoot.height } ?: 0f
    val metrics = buildJsonObject {
        put("width", width)
        put("height", 800)
        put("isDark", isDark)
        put("state", state)
        put("fixtureChats", fixtureChats)
        put("visibleChatRows", rows.count { it.boundsInRoot.height >= it.size.height - 0.5f })
        put("maximumVisibleRowHeight", rowHeight)
        put("focusedNodes", JsonArray(focusedAuditNodes().map(::JsonPrimitive)))
        put(
            "bounds",
            buildJsonObject {
                auditTags.forEach { tag ->
                    val bounds = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                    if (bounds != null) {
                        put(
                            tag,
                            buildJsonObject {
                                put("x", bounds.left)
                                put("y", bounds.top)
                                put("width", bounds.width)
                                put("height", bounds.height)
                            },
                        )
                    }
                }
            },
        )
    }
    File(directory, "$name.json").writeText(metrics.toString())
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.focusedAuditNodes(): List<String> =
    onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().map {
        it.config.getOrNull(SemanticsProperties.TestTag)
            ?: it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: it.config.getOrNull(SemanticsProperties.Text)?.joinToString { text -> text.text }
            ?: "focus-node-${it.id}"
    }

/** Only presentation reducers are exercised; navigation in this fixture has no runtime side effects. */
private fun AiStudioScreenState.auditIntent(intent: AiStudioScreenIntent): AiStudioScreenState = when (intent) {
    is AiStudioScreenIntent.Sidebar -> copy(sidebar = sidebar.reduce(intent))

    is AiStudioScreenIntent.DraftChanged -> withDraft(intent.paneId, intent.text)

    is AiStudioScreenIntent.OpenSession -> copy(panes = persistentListOf(PaneUi(0, sessionId = intent.sessionId)))

    is AiStudioScreenIntent.Navigation,
    is AiStudioScreenIntent.Composer,
    is AiStudioScreenIntent.SessionAction,
    -> this
}

internal fun desktopAuditDirectory(): File {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    val phase = System.getenv("HEARTBEAT_DESKTOP_AUDIT_PHASE") ?: "after"
    return File(root, "output/desktop-redesign-2026-09-28/$phase").apply { mkdirs() }
}

private val auditTags = listOf(
    "studio-rail", "studio-sidebar", "sidebar-footer", "sidebar-search-open", "pane-0", "pane-header-0",
    "pane-footer-0", "composer-0",
    "transcript-audit-0", "new-session-hero", "session-audit-0", "sidebar-search", "research-mode",
    "message-header:audit-answer-5", "message-footer:audit-answer-5",
)
private val auditExits = StudioExits(
    onBack = {},
    onOpenToggles = {},
    onOpenProfileSettings = {},
    onOpenConnections = {},
    onOpenResearch = {},
    onOpenSettings = {},
)
