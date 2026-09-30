package io.aequicor.heartbeat.feature.searchengine.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.searchengine.api.SearchConnection
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchOperation
import io.aequicor.heartbeat.feature.searchengine.api.SearchSettings
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.CheckPhase
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.SearchSettingsIntent
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.SearchSettingsState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProfileSettingsUiTest {
    private val loaded = SearchSettingsState(
        settings = SearchSettings(search = SearchConnection(hasKey = true), contents = SearchConnection()),
        searchHost = "https://api.querit.ai",
        contentsHost = "https://api.querit.ai",
        searchCheck = CheckPhase.Failure,
        searchCheckFailure = SearchFailure.Authentication,
    )

    @Test
    fun `key is submitted once and cleared, preference and checks emit intents`() = runSkikoComposeUiTest(
        size = Size(1000f, 1400f),
    ) {
        val intents = mutableListOf<SearchSettingsIntent>()
        setContent { HbTheme { ProfileSettingsContent(loaded, { intents += it }, onBack = null) } }
        onNodeWithTag("check:${SearchOperation.Contents}").assertIsNotEnabled()
        onNodeWithTag("key:${SearchOperation.Contents}").performTextInput("secret")
        onNodeWithTag("save-key:${SearchOperation.Contents}").performClick()
        onNodeWithTag("save-key:${SearchOperation.Contents}").assertIsNotEnabled()
        onNodeWithTag("prefer-native").performClick()
        onNodeWithTag("check:${SearchOperation.Search}").performClick()
        onNodeWithTag("remove-key:${SearchOperation.Search}").performClick()
        runOnIdle {
            val saved = assertIs<SearchSettingsIntent.SaveKey>(intents[0])
            assertEquals(SearchOperation.Contents, saved.operation)
            assertEquals(SearchSettingsIntent.PreferNative(false), intents[1])
            assertEquals(SearchSettingsIntent.Check(SearchOperation.Search), intents[2])
            val removed = assertIs<SearchSettingsIntent.SaveKey>(intents[3])
            assertNull(removed.key)
        }
    }

    @Test
    fun `embedded content has no back while the standalone screen has one`() = runSkikoComposeUiTest {
        var backs = 0
        setContent { HbTheme { ProfileSettingsContent(loaded, {}, onBack = { backs++ }) } }
        onNodeWithTag("profile-settings-back").performClick()
        runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `renders snapshots`() {
        for (width in listOf(1280, 900, 420)) {
            for (isDark in listOf(false, true)) {
                render(width, isDark) { ProfileSettingsContent(loaded, {}, onBack = null) }
            }
        }
    }

    private fun render(width: Int, isDark: Boolean, content: @Composable () -> Unit) =
        runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                ) { HbTheme(darkTheme = isDark) { content() } }
            }
            waitForIdle()
            val file = File("build/reports/snapshots/search-$width-${if (isDark) "dark" else "light"}.png")
            file.parentFile.mkdirs()
            assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", file))
        }
}
