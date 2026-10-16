package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSecurityLevelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSecurityScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioSecurityScreenState
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioSecurityUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useRussianStrings() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `each level is selectable and tab moves keyboard focus between choices`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val selections = mutableListOf<StudioSecurityScreenIntent>()
            setContent {
                HbTheme { StudioSecurityContent(StudioSecurityScreenState(isLoaded = true), selections::add, null) }
            }
            onNodeWithTag("studio-security-Automatic").assertIsSelected()
            onNodeWithTag("studio-security-Ask").performClick()
            onNodeWithTag("studio-security-Ask").performSemanticsAction(SemanticsActions.RequestFocus)
            onNodeWithTag("studio-security-Ask").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("studio-security-Automatic").assertIsFocused()
            onNodeWithTag("studio-security-Automatic").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("studio-security-Trust").performClick()
            runOnIdle {
                assertEquals<List<StudioSecurityScreenIntent>>(
                    StudioSecurityLevelUi.entries.map { StudioSecurityScreenIntent.Select(it) },
                    selections,
                )
            }
        }

    @Test
    fun `pending write disables choices and keeps the last saved selection`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            setContent {
                HbTheme {
                    StudioSecurityContent(
                        StudioSecurityScreenState(level = StudioSecurityLevelUi.Ask, isLoaded = true, isSaving = true),
                        {},
                        null,
                    )
                }
            }
            onNodeWithTag("studio-security-Ask").assertIsSelected()
            StudioSecurityLevelUi.entries.forEach {
                onNodeWithTag("studio-security-${it.name}").assertIsNotEnabled()
            }
        }

    @Test
    fun `failed loading offers retry while policy editing stays disabled`() =
        runSkikoComposeUiTest(size = Size(420f, 800f)) {
            val intents = mutableListOf<StudioSecurityScreenIntent>()
            setContent {
                HbTheme {
                    StudioSecurityContent(
                        StudioSecurityScreenState(level = StudioSecurityLevelUi.Ask, isLoadFailed = true),
                        intents::add,
                        null,
                    )
                }
            }
            onNodeWithTag("studio-security-Trust").assertIsNotEnabled()
            onNodeWithTag("studio-security-retry").performClick()
            runOnIdle {
                assertEquals<List<StudioSecurityScreenIntent>>(listOf(StudioSecurityScreenIntent.Retry), intents)
            }
        }

    @Test
    fun `renders security settings at desktop and compact widths in both themes`() {
        for (width in listOf(1280, 420)) {
            for (isDark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = isDark) {
                                StudioSecurityContent(StudioSecurityScreenState(isLoaded = true), {}, null)
                            }
                        }
                    }
                    waitForIdle()
                    val file = File("build/reports/snapshots/security-$width-${if (isDark) "dark" else "light"}.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(captureToImage().toAwtImage(), "png", file)
                }
            }
        }
    }
}
