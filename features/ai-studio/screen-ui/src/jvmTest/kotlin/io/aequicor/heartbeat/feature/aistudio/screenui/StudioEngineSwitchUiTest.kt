package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_switch_workspace
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EngineSwitchUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StudioEngineSwitchUiTest {
    @Test
    fun `desktop menu explains unavailable routes and selection carries source without submitting`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(1000f, 800f)) {
                val pane = PaneUi(0, sessionId = "chat")
                val a = ModelUi("a", "Engine A", isLocalProjectSupported = true, engineRoute = "one")
                val sibling = a.copy(id = "sibling", name = "Another model of Engine A")
                val b = a.copy(id = "b", name = "Engine B", engineRoute = "two")
                val unsupported = b.copy(id = "cloud", name = "Cloud", isLocalProjectSupported = false)
                var state by mutableStateOf(
                    AiStudioScreenState(
                        isEngineSwitchingAvailable = true,
                        panes = persistentListOf(pane),
                        sessions = persistentListOf(
                            SessionUi("chat", "Task", "project", Instant.DISTANT_PAST, modelId = "a"),
                        ),
                        models = persistentListOf(a, sibling, b, unsupported),
                    ).withDraft(0, "Preserved draft"),
                )
                val events = mutableListOf<AiStudioScreenIntent>()
                var workspaceReason = ""
                var sendLabel = ""
                setContent {
                    workspaceReason = stringResource(Res.string.engine_switch_workspace)
                    sendLabel = stringResource(Res.string.composer_send)
                    HbTheme(darkTheme = dark) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                            EngineSwitchNotice(state.engineSwitches["chat"])
                            StudioComposer(state.paneContent(pane), events::add, isCompact = false)
                        }
                    }
                }
                onNodeWithTag("model-chip").performClick()
                onNodeWithText("Cloud").assertIsDisplayed().assertIsNotEnabled()
                onNodeWithText(workspaceReason).assertIsDisplayed()
                saveSwitchImage("menu-$dark")
                onNodeWithText("Engine B").assertIsDisplayed()
                onNodeWithText(sibling.name).performClick()
                runOnIdle {
                    assertEquals(
                        listOf<AiStudioScreenIntent>(AiStudioScreenIntent.SelectModel("sibling", 0, "a", "chat")),
                        events,
                    )
                    state = state.copy(engineSwitches = persistentMapOf("chat" to EngineSwitchUi(isPending = true)))
                }
                onNodeWithTag("engine-switch-notice").assertIsDisplayed()
                onNodeWithContentDescription("Engine A").assertIsNotEnabled()
                onNodeWithContentDescription(sendLabel).assertIsNotEnabled()
                onNodeWithText("Preserved draft").assertIsDisplayed()
                saveSwitchImage("pending-$dark")
            }
        }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.saveSwitchImage(name: String) {
    val directory = File("build/screenshots/engine-switch").apply { mkdirs() }
    check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png")))
}
