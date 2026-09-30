package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.AvailabilityUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectionRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.MethodKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.MethodRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelsPaneUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.WizardStep
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Renders the settings space and the wizard at desktop and compact widths into `build/reports/snapshots`. */
@OptIn(ExperimentalTestApi::class)
class ConnectionsSnapshotTest {
    private val models = persistentListOf(
        ModelRowUi("gpt-5", "GPT-5", 400_000, isEnabled = true, isDefault = true),
        ModelRowUi("gpt-5-mini", "GPT-5 mini", 400_000, isEnabled = true),
        ModelRowUi("o4-reasoning-extended-preview", "o4 reasoning extended preview", null, isEnabled = false),
    )
    private val connections = EngineConnectionsScreenState(
        engines = persistentListOf(
            EngineRowUi("koog", "Koog", AvailabilityUi.Available, 1, isConnectable = true),
            EngineRowUi("codex", "Codex", AvailabilityUi.Unavailable, 0, isConnectable = true),
        ),
        selectedEngine = "koog",
        connections = persistentListOf(
            ConnectionRowUi("c1", "Рабочий OpenAI", "OpenAI", MethodKindUi.ApiKey, "https://api.openai.com", true, 2),
        ),
        selectedConnection = "c1",
        models = ModelsPaneUi(models, isStale = false, isNeverSynced = false),
        isLoading = false,
    )
    private val wizard = ConnectWizardScreenState(
        step = WizardStep.Method,
        engineTitle = "Koog",
        methods = persistentListOf(
            MethodRowUi("openai", "OpenAI", MethodKindUi.ApiKey, "https://api.openai.com", false, false, "https://x"),
            MethodRowUi("ollama", "Ollama", MethodKindUi.NoAuth, "http://localhost:11434", true, false, null),
        ),
        selectedMethod = "openai",
        isBusy = false,
    )

    @Test
    fun `renders snapshots`() {
        for (width in listOf(1280, 900, 420)) {
            for (isDark in listOf(false, true)) {
                render("connections", width, isDark) { EngineConnectionsContent(connections, {}, {}, {}) }
                render("wizard", width, isDark) { ConnectWizardContent(wizard, {}) }
            }
        }
    }

    private fun render(name: String, width: Int, isDark: Boolean, content: @Composable () -> Unit) =
        runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                ) { HbTheme(darkTheme = isDark) { content() } }
            }
            waitForIdle()
            val file = File("build/reports/snapshots/$name-$width-${if (isDark) "dark" else "light"}.png")
            file.parentFile.mkdirs()
            ImageIO.write(captureToImage().toAwtImage(), "png", file)
        }
}
