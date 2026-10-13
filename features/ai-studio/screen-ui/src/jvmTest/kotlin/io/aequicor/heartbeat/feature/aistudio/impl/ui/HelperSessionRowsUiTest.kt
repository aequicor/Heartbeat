package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_pin
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_unpin
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_awaiting_permission
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class HelperSessionRowsUiTest {
    @Test
    fun `nested rows hide pin and unpin while root or orphan rows retain the action`() = runSkikoComposeUiTest {
        val child = SessionUi("child", "Child", null, Instant.DISTANT_PAST, parentChatId = "parent", depth = 1)
        var nested = emptyList<String>()
        var pinned = emptyList<String>()
        var search = emptyList<String>()
        var root = emptyList<String>()
        var pinLabel = ""
        var unpinLabel = ""
        setContent {
            HbTheme {
                pinLabel = stringResource(Res.string.action_pin)
                unpinLabel = stringResource(Res.string.action_unpin)
                nested = sessionMenu(child, false).map { it.label }
                pinned = sessionMenu(child.copy(isPinned = true), false).map { it.label }
                search = sessionMenu(child.copy(depth = 0, isNestedInSidebar = true), false).map { it.label }
                root = sessionMenu(child.copy(depth = 0), false).map { it.label }
            }
        }
        runOnIdle {
            assertFalse(pinLabel in nested)
            assertFalse(unpinLabel in pinned)
            assertFalse(pinLabel in search)
            assertTrue(pinLabel in root)
        }
    }

    @Test
    fun `visual depth is bounded for sidebar and rename without changing model depth`() {
        val child = SessionUi("child", "Child", null, Instant.DISTANT_PAST, depth = 100)
        assertEquals(4, sessionIndentation(0, child.depth))
        assertEquals(4, sessionIndentation(1, child.depth))
        assertEquals(3, sessionIndentation(1, 2))
        assertEquals(100, child.depth)
    }

    @Test
    fun `nested helper permission replaces running indicator and survives hover focus and navigation`() {
        for (isDark in listOf(false, true)) {
            for (width in listOf(420f, 1280f)) {
                runSkikoComposeUiTest(size = Size(width, 240f)) {
                    var isWaiting by mutableStateOf(true)
                    var selected: String? = null
                    var label = ""
                    setContent {
                        label = stringResource(Res.string.session_awaiting_permission)
                        HbTheme(darkTheme = isDark, dimensions = HbDimensions.Desktop) {
                            HbColumn(Modifier.background(HbTheme.surfaces.sidebar)) {
                                val rows = SessionRows(null, null, null, {}, false) {
                                    if (it is AiStudioScreenIntent.OpenSession) selected = it.sessionId
                                }
                                rows.SessionRow(SessionUi("parent", "Parent", null, Instant.DISTANT_PAST), "recent")
                                rows.SessionRow(
                                    SessionUi(
                                        "helper",
                                        "Helper",
                                        null,
                                        Instant.DISTANT_PAST,
                                        parentChatId = "parent",
                                        depth = 1,
                                        isRunning = true,
                                        isAwaitingPermission = isWaiting,
                                    ),
                                    "recent",
                                )
                            }
                        }
                    }
                    val row = onNodeWithTag("session-helper")
                    val permission = onNodeWithTag("session-permission-helper", useUnmergedTree = true)
                    val running = onNodeWithTag("session-running-helper", useUnmergedTree = true)
                    permission.assertIsDisplayed()
                    running.assertDoesNotExist()
                    row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
                    row.performMouseInput { moveTo(center) }
                    row.requestFocus().assertIsFocused()
                    permission.assertIsDisplayed()
                    row.performClick()
                    runOnIdle { assertEquals("helper", selected) }
                    val parentLabel = onNodeWithTag("session-parent").fetchSemanticsNode().boundsInRoot
                    val helperLabel = row.fetchSemanticsNode().boundsInRoot
                    assertTrue(helperLabel.top > parentLabel.top)
                    val image = File("build/previews/helper-permission-${width.toInt()}-$isDark.png")
                    image.parentFile.mkdirs()
                    check(ImageIO.write(captureToImage().toAwtImage(), "png", image))
                    runOnIdle { isWaiting = false }
                    permission.assertDoesNotExist()
                    running.assertIsDisplayed()
                }
            }
        }
    }
}
