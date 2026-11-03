package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
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
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineConnectionStatusUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EnginePanelTabUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceFileUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceInventoryUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceKindUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceOriginUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceStateUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.managedCodexWithCopy
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.managedSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.reflect
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
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
            EngineItemUi("codex", "Codex", EngineConnectionStatusUi.Connected),
            EngineItemUi("claude", "Claude Code", EngineConnectionStatusUi.Off),
            EngineItemUi("koog", "Koog", EngineConnectionStatusUi.NotConnected),
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
    fun `four panel tabs keep embedded connections in a bounded content area`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            var panel by mutableStateOf(
                EngineControlPanelState(CodexId.value, isConnectionsEnabled = true)
                    .reflect(CodexId, managedSnapshot(managedCodexWithCopy())),
            )
            setContent {
                HbTheme(dimensions = HbDimensions.Desktop) {
                    EngineControlPanelContent(
                        panel,
                        { if (it is EngineControlPanelIntent.SelectTab) panel = panel.copy(openTab = it.tab) },
                        connections = {
                            Box(Modifier.fillMaxSize().testTag("test-embedded-connections"))
                        },
                    )
                }
            }
            listOf("overview", "connections", "resources", "settings").forEach {
                onNodeWithTag("engine-tab-$it").assertExists()
            }
            onNodeWithTag("engine-tab-connections").performClick()
            onNodeWithTag("test-embedded-connections").assertExists()
            assertTrue(onNodeWithTag("test-embedded-connections").fetchSemanticsNode().boundsInRoot.height > 0f)
            onNodeWithTag("engine-tab-resources").performClick()
            onNodeWithTag("test-embedded-connections").assertDoesNotExist()
            onNodeWithTag("engine-tab-settings").performClick()
            onNodeWithTag("engine-control-pages").assertDoesNotExist()
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
            onNodeWithText("Off").assertExists()
            onNodeWithText("Koog").assertExists()
            onNodeWithText("Built-in engine").assertDoesNotExist()
            onNodeWithText("No connection required").assertDoesNotExist()
            onNodeWithTag("engine-status-check:codex", useUnmergedTree = true).assertExists()
            onNodeWithTag("engine-status-check:claude", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("engine-status-check:koog", useUnmergedTree = true).assertDoesNotExist()
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
                EngineItemUi("engine-$index", "Engine $index", EngineConnectionStatusUi.NotConnected)
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
                val installed = managedCodexWithCopy().copy(login = LoginState.SignedIn())
                val panel = EngineControlPanelState(CodexId.value).reflect(CodexId, managedSnapshot(installed))
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

    @Test
    fun `resource tab renders supported inventory and empty states in both themes`() {
        val ready = EngineControlPanelState(CodexId.value).reflect(CodexId, managedSnapshot(managedCodexWithCopy()))
        val files = (1..30).map {
            ResourceFileUi(
                ResourceKindUi.Skill,
                ResourceOriginUi.Profile,
                "skills/example-$it/SKILL.md",
                "A deliberately long resource name for profile instruction $it",
                ResourceStateUi.Enabled,
                "Saved profile instruction, selected by the connector configuration.",
            )
        }.toPersistentList()
        for (width in listOf(420, 1280)) {
            for (dark in listOf(false, true)) {
                for (isEmpty in listOf(false, true)) {
                    runSkikoComposeUiTest(size = Size(width.toFloat(), 600f)) {
                        val state = ready.copy(
                            openTab = EnginePanelTabUi.Resources,
                            hasResourceInventory = true,
                            resources = ResourceInventoryUi(if (isEmpty) persistentListOf() else files, 0, false),
                        )
                        setContent {
                            HbTheme(dimensions = HbDimensions.Desktop, darkTheme = dark) {
                                EngineControlPanelContent(state, {}, isEmbedded = true)
                            }
                        }
                        onNodeWithTag("resource-inventory").assertExists()
                        val directory = File("build/reports/ui/engine-mcp").apply { mkdirs() }
                        ImageIO.write(
                            captureToImage().toAwtImage(),
                            "png",
                            File(directory, "resources-$width-$dark-$isEmpty.png"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `disabled overview settings and removal render in both themes`() {
        val installed = managedCodexWithCopy()
        val ready = EngineControlPanelState(CodexId.value).reflect(CodexId, managedSnapshot(installed))
        val disabled = installed.copy(
            enablement = EngineEnablement(false, setOf(DisabledReason.DisabledByUser)),
        )
        val variants = listOf(
            "disabled" to EngineControlPanelState(CodexId.value).reflect(CodexId, managedSnapshot(disabled)),
            "settings" to ready.copy(openTab = EnginePanelTabUi.Settings),
            "removal" to ready.copy(confirmAction = EngineActionUi.Uninstall),
        )
        listOf(false, true).forEach { dark ->
            variants.forEach { (name, panel) ->
                runSkikoComposeUiTest(size = Size(1016f, 800f)) {
                    setContent {
                        HbTheme(dimensions = HbDimensions.Desktop, darkTheme = dark) {
                            EngineControlPanelContent(panel, {}, isEmbedded = true)
                        }
                    }
                    onNodeWithTag("engine-control-panel").assertExists()
                    val directory = File("build/reports/ui/engine-mcp").apply { mkdirs() }
                    check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name-$dark.png")))
                }
            }
        }
    }
}
