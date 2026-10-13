package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_rename
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_running
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Running state remains visible beside actions throughout desktop row interaction. */
@OptIn(ExperimentalTestApi::class)
class SessionRowsUiTest {
    @Test
    fun `selected running session keeps its indicator while hovered focused and showing its menu`() {
        for (isDark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(420f, 360f)) {
                var selectedId by mutableStateOf<String?>(null)
                var openMenu by mutableStateOf<String?>(null)
                var isRunning by mutableStateOf(true)
                var runningLabel = ""
                var renameLabel = ""
                setContent {
                    runningLabel = stringResource(Res.string.session_running)
                    renameLabel = stringResource(Res.string.action_rename)
                    SessionRowTestHost(isDark, selectedId, openMenu, isRunning, { openMenu = it }, { selectedId = it })
                }
                val row = onNodeWithTag("session-running")
                val indicator = onNodeWithTag("session-running-running", useUnmergedTree = true)
                val archive = onNodeWithTag("session-archive-recent:running", useUnmergedTree = true)
                val menu = onNode(
                    hasAnyAncestor(hasTestTag("session-menu-recent:running")) and hasClickAction(),
                    useUnmergedTree = true,
                )
                indicator.assertIsDisplayed()
                archive.assertDoesNotExist()
                row.performMouseInput { moveTo(center) }
                indicator.assertIsDisplayed()
                archive.assertIsDisplayed()
                row.performMouseInput { click() }
                row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, runningLabel))
                row.requestFocus().assertIsFocused()
                indicator.assertIsDisplayed()
                onAllNodesWithTag("session-running-running", useUnmergedTree = true).assertCountEquals(1)
                val image = File("build/previews/session-running-selected-${if (isDark) "dark" else "light"}.png")
                image.parentFile.mkdirs()
                check(ImageIO.write(captureToImage().toAwtImage(), "png", image))
                row.performKeyInput { pressKey(Key.Tab) }
                archive.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
                menu.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
                runOnIdle { assertEquals("recent:running", openMenu) }
                onNodeWithText(renameLabel).assertIsDisplayed()
                indicator.assertIsDisplayed()
                onNodeWithText(renameLabel).performKeyInput { pressKey(Key.Escape) }
                runOnIdle { assertEquals(null, openMenu) }
                row.performMouseInput { exit() }
                onNodeWithTag("outside").requestFocus()
                indicator.assertIsDisplayed()
                archive.assertDoesNotExist()
                runOnIdle { isRunning = false }
                indicator.assertDoesNotExist()
                row.assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
                row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                row.performMouseInput { moveTo(center) }
                archive.assertIsDisplayed()
                menu.assertIsDisplayed()
            }
        }
    }
}

@Composable
private fun SessionRowTestHost(
    isDark: Boolean,
    selectedId: String?,
    openMenu: String?,
    isRunning: Boolean,
    onMenu: (String?) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    HbTheme(
        darkTheme = isDark,
        dimensions = HbDimensions.Desktop,
        motion = HbMotion(isReducedMotion = true),
    ) {
        HbColumn(Modifier.background(HbTheme.surfaces.sidebar)) {
            SessionRows(
                selectedId = selectedId,
                renaming = null,
                openMenu = openMenu,
                onMenu = onMenu,
                isOpenBesideAllowed = false,
                onIntent = { if (it is AiStudioScreenIntent.OpenSession) onOpenSession(it.sessionId) },
            ).SessionRow(
                SessionUi(
                    id = "running",
                    title = "Running session",
                    projectId = null,
                    updatedAt = Instant.DISTANT_PAST,
                    isRunning = isRunning,
                ),
                section = "recent",
            )
            HbButton("Outside", {}, Modifier.testTag("outside"))
        }
    }
}
