package io.aequicor.heartbeat.platform.dibundle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlPanelRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

/** Real bundle graph and public nested routes, using an empty temporary profile and no model requests. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class EnginePanelEmbeddedUiTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val lifecycle = LifecycleRegistry()
    private lateinit var panel: ComposableComponent

    @BeforeTest
    fun prepare() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val toggles = (app as TestToggleAccessors).toggleControl
        listOf(
            AiEngines,
            KoogEngineEnabled,
            PiEnabled,
            EngineManagementEnabled,
            EngineConnectionsEnabled,
            EngineConfigurationEnabled,
        ).forEach { toggles.setOverride(it, true) }
        val profile = app.profileSessions.open(ProfileId("embedded-ui"))
        lifecycle.resume()
        val host = (profile.graph as ProfileNavigation).navigation.create(
            DefaultComponentContext(lifecycle),
            initial = listOf(EngineControlPanelRoute(EngineId("koog"))),
            name = "profile",
        )
        panel = assertIs<ComposableComponent>(host.stack.value.active.instance)
    }

    @AfterTest
    fun finish() = runTest {
        lifecycle.destroy()
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `real engine panel embeds connections through its public route`() = render(1280, 800)

    @Test
    fun `real engine panel keeps its connections bounded at compact width`() = render(420, 240)

    private fun render(width: Int, height: Int) = runSkikoComposeUiTest(
        size = Size(width.toFloat(), height.toFloat()),
    ) {
        var isDark by mutableStateOf(false)
        setContent { HbTheme(darkTheme = isDark) { panel.Content(Modifier) } }
        waitUntil(
            timeoutMillis = 5_000,
        ) { onAllNodesWithTag("engine-tab-connections").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("engine-tab-connections").performClick()
        waitUntil(
            timeoutMillis = 5_000,
        ) { onAllNodesWithTag("settings-add-connection").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("settings-add-connection").performScrollTo().assertIsDisplayed()
        onNodeWithTag("settings-add-engine").assertDoesNotExist()
        onNodeWithTag("settings-engine:koog").assertDoesNotExist()
        val directory = File("build/reports/ui/engine-panel").apply { mkdirs() }
        for (dark in listOf(false, true)) {
            isDark = dark
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "connections-$width-$dark.png"))
        }
        onNodeWithTag("engine-tab-resources").performClick()
        for (dark in listOf(false, true)) {
            isDark = dark
            waitForIdle()
            ImageIO.write(
                captureToImage().toAwtImage(),
                "png",
                File(directory, "resources-unsupported-$width-$dark.png"),
            )
        }
        onNodeWithTag("engine-tab-connections").performClick()
        onNodeWithTag("settings-add-connection").assertExists()
    }
}
