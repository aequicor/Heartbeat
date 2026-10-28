package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.CodexId
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineStatusUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.managedSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.reflect
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EngineBrowserUiTest {
    private val originalLocale = Locale.getDefault()
    private val loaded = EngineControlListState(
        engines = persistentListOf(
            EngineItemUi("codex", "Codex", EngineStatusUi.On, null, false, isConnected = true),
            EngineItemUi("claude", "Claude Code", EngineStatusUi.On, null, false),
            EngineItemUi("koog", "Koog", EngineStatusUi.On, null, false, isBuiltIn = true),
        ),
        isLoading = false,
    )

    @BeforeTest
    fun useEnglishResources() {
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `engine selection keeps the list beside details and shows connection-specific statuses`() =
        runSkikoComposeUiTest(size = Size(1016f, 800f)) {
            var selected by mutableStateOf<String?>("codex")
            val selectedEvents = mutableListOf<String>()
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    EngineControlBrowserContent(loaded, selected, {
                        selectedEvents += it
                        selected = it
                    }) { HbText("Selected: $selected") }
                }
            }
            onNodeWithTag("engine-control:codex").assertIsSelected()
            onNodeWithText("Connected").assertExists()
            onNodeWithText("Not connected").assertExists()
            onNodeWithText("Built-in engine").assertExists()
            onNodeWithText("No connection required").assertExists()
            onNodeWithText("Koog").assertDoesNotExist()
            onNodeWithTag("engine-control:claude").performClick().assertIsSelected()
            onNodeWithText("Selected: claude").assertExists()
            onNodeWithTag("engine-control:codex").assertExists()
            runOnIdle { assertEquals(listOf("claude"), selectedEvents) }
            val list = onNodeWithTag("engine-browser-list").fetchSemanticsNode().boundsInRoot
            val details = onNodeWithTag("engine-browser-details").fetchSemanticsNode().boundsInRoot
            assertTrue(list.right <= details.left)
            assertEquals(list.top, details.top)
        }

    @Test
    fun `short browser list scrolls to and selects its last engine`() =
        runSkikoComposeUiTest(size = Size(1016f, 180f)) {
            val engines = (0..20).map { index ->
                EngineItemUi("engine-$index", "Engine $index", EngineStatusUi.On, null, false)
            }.toPersistentList()
            val state = loaded.copy(engines = engines)
            var selected by mutableStateOf<String?>(null)
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    EngineControlBrowserContent(state, selected, { selected = it }) { HbText("Selected: $selected") }
                }
            }
            onNodeWithTag("engine-browser-list-scroll").performScrollToIndex(engines.lastIndex)
            onNodeWithTag("engine-control:engine-20").performClick().assertIsSelected()
            onNodeWithText("Selected: engine-20").assertExists()
        }

    @Test
    fun `failed off and empty list states preserve the details region and offer retry`() =
        runSkikoComposeUiTest(size = Size(1016f, 800f)) {
            var state by mutableStateOf(loaded.copy(hasLoadFailure = true))
            var retries = 0
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    EngineControlBrowserContent(state, "codex", {}, onRetry = { retries++ }) {
                        HbText("Existing details")
                    }
                }
            }
            onNodeWithText("Retry").performClick()
            runOnIdle { assertEquals(1, retries) }
            onNodeWithTag("engine-control:codex").assertDoesNotExist()
            onNodeWithText("Existing details").assertExists()
            runOnIdle { state = loaded.copy(isOff = true) }
            onNodeWithText("Engine management is off.").assertExists()
            onNodeWithTag("engine-control:codex").assertDoesNotExist()
            runOnIdle { state = loaded.copy(engines = persistentListOf()) }
            onNodeWithText("No engines are registered.").assertExists()
            onNodeWithText("Existing details").assertExists()
        }

    @Test
    fun `desktop light and dark engine browser renders reviewable previews`() {
        listOf(false, true).forEach { dark ->
            runSkikoComposeUiTest(size = Size(1016f, 800f)) {
                val panel = EngineControlPanelState(CodexId.value).reflect(CodexId, managedSnapshot())
                setContent {
                    HbTheme(dimensions = HbDimensions.Desktop, darkTheme = dark) {
                        EngineControlBrowserContent(loaded, "codex", {}) {
                            EngineControlPanelContent(panel, {}, Modifier, isEmbedded = true)
                        }
                    }
                }
                onNodeWithTag("engine-browser-list").assertExists()
                onNodeWithTag("engine-browser-details").assertExists()
                val directory = File("build/reports/ui/engine-mcp").apply { mkdirs() }
                ImageIO.write(
                    captureToImage().toAwtImage(),
                    "png",
                    File(directory, "browser-${if (dark) "dark" else "light"}.png"),
                )
            }
        }
    }
}
