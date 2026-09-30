package io.aequicor.heartbeat.feature.togglespanel.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelScreenState
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.toUi
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Renders the panel at desktop and compact widths in both themes into `build/reports/snapshots`. */
@OptIn(ExperimentalTestApi::class)
class TogglesPanelSnapshotTest {
    private val state = TogglesPanelScreenState(
        rows = persistentListOf(
            ToggleState(
                FeatureToggle.Flag("ai.engine_connections", "Настройка подключений ИИ-движков"),
                true,
                ToggleSource.LocalOverride,
            ).toUi(),
            ToggleState(
                FeatureToggle.Flag("ai.koog", "Движок Koog: OpenAI, Anthropic и локальный Ollama"),
                false,
                ToggleSource.Default,
            ).toUi(),
            ToggleState(
                FeatureToggle.Choice(
                    "ai_studio.mode",
                    "Режим студии с очень длинным описанием, чтобы проверить перенос",
                    listOf("Демо", "Профиль"),
                ),
                "Демо",
                ToggleSource.Default,
            ).toUi(),
            ToggleState(FeatureToggle.Flag("welcome.cinematic_intro", "Intro"), true, ToggleSource.Default).toUi(),
        ),
        isLoading = false,
    )

    @Test
    fun `renders snapshots`() {
        for (width in listOf(1280, 900, 420)) {
            for (isDark in listOf(false, true)) render(width, isDark)
        }
    }

    private fun render(width: Int, isDark: Boolean) = runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme(darkTheme = isDark) { TogglesPanelContent(state, {}, {}) }
            }
        }
        waitForIdle()
        val file = File("build/reports/snapshots/toggles-$width-${if (isDark) "dark" else "light"}.png")
        file.parentFile.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", file)
    }
}
