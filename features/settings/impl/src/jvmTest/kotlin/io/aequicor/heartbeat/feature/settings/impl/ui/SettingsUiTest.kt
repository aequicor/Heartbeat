package io.aequicor.heartbeat.feature.settings.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsScreenState
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsSectionUi
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {
    private val state = SettingsScreenState(
        sections = persistentListOf(SettingsSectionUi.Models, SettingsSectionUi.Search, SettingsSectionUi.FeatureFlags),
        selected = SettingsSectionUi.Models,
    )

    @Test
    fun `wide window selects sections, goes back from the list and closes with Esc`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val selected = mutableListOf<SettingsSectionUi>()
            val backs = mutableListOf<Boolean>()
            var closes = 0
            setContent {
                HbTheme {
                    SettingsContent(state, selected::add, backs::add, { closes++ }) { HbText("section content") }
                }
            }
            onNodeWithTag("settings-section:Models").assertIsSelected()
            onNodeWithTag("settings-section:FeatureFlags").performClick()
            onNodeWithTag("settings-back").performClick()
            onNodeWithTag("settings").performKeyInput { pressKey(Key.Escape) }
            runOnIdle {
                assertEquals(listOf(SettingsSectionUi.FeatureFlags), selected)
                assertEquals(1, closes)
                assertEquals(listOf(false), backs)
            }
        }

    @Test
    fun `compact window shows the list first, then the section with back`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val selected = mutableListOf<SettingsSectionUi>()
            val backs = mutableListOf<Boolean>()
            setContent {
                HbTheme { SettingsContent(state, selected::add, backs::add, {}) { HbText("section content") } }
            }
            onNodeWithTag("settings-section").assertDoesNotExist()
            onNodeWithTag("settings-section:Search").performClick()
            runOnIdle { assertEquals(listOf(SettingsSectionUi.Search), selected) }
        }

    @Test
    fun `compact chosen section has a back arrow`() = runSkikoComposeUiTest(size = Size(420f, 800f)) {
        val backs = mutableListOf<Boolean>()
        setContent {
            HbTheme {
                SettingsContent(state.copy(isSectionChosen = true), {}, backs::add, {}) { HbText("section content") }
            }
        }
        onNodeWithTag("settings-section").assertExists()
        onNodeWithTag("settings-back").performClick()
        runOnIdle { assertEquals(listOf(true), backs) }
    }

    @Test
    fun `renders snapshots`() {
        for (width in listOf(1280, 420)) {
            for (isDark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = isDark) { SettingsContent(state, {}, {}, {}) { } }
                        }
                    }
                    waitForIdle()
                    val file = File("build/reports/snapshots/settings-$width-${if (isDark) "dark" else "light"}.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(captureToImage().toAwtImage(), "png", file)
                }
            }
        }
    }
}
