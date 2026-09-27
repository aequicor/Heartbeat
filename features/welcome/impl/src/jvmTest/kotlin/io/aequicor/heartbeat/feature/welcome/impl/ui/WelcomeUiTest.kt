package io.aequicor.heartbeat.feature.welcome.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.welcome.impl.presentation.store.WelcomePhase
import io.aequicor.heartbeat.feature.welcome.impl.presentation.store.WelcomeScreenIntent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WelcomeUiTest {
    @Test
    fun `six second scene reveals actions and stops at the final frame`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        mainClock.autoAdvance = false
        var phase by mutableStateOf(WelcomePhase.Intro)
        val events = mutableListOf<WelcomeScreenIntent>()
        setContent {
            HbTheme {
                WelcomeContent(phase, {
                    events += it
                    if (it == WelcomeScreenIntent.IntroFinished) phase = WelcomePhase.Ready
                })
            }
        }
        mainClock.advanceTimeBy(2100)
        save("intro-light", captureToImage().toAwtImage())
        mainClock.advanceTimeBy(2400)
        save("intro-title", captureToImage().toAwtImage())
        runOnIdle { assertFalse(events.contains(WelcomeScreenIntent.IntroFinished)) }
        mainClock.advanceTimeBy(1420)
        runOnIdle { assertFalse(events.contains(WelcomeScreenIntent.IntroFinished)) }
        mainClock.advanceTimeBy(160)
        onNodeWithTag("welcome-studio").assertIsEnabled().assertIsFocused()
        onNodeWithTag("welcome-toggles").assertIsEnabled()
        val final = captureToImage().toAwtImage()
        save("welcome-wide", final)
        mainClock.advanceTimeBy(10000)
        val later = captureToImage().toAwtImage()
        assertTrue(
            final.getRGB(0, 0, final.width, final.height, null, 0, final.width)
                .contentEquals(later.getRGB(0, 0, later.width, later.height, null, 0, later.width)),
        )
        assertEquals(1, events.count { it == WelcomeScreenIntent.IntroFinished })
        onNodeWithTag("welcome-studio").performClick()
        assertEquals(WelcomeScreenIntent.OpenStudio, events.last())
    }

    @Test
    fun `escape skips immediately and late frames do not finish again`() = runSkikoComposeUiTest {
        mainClock.autoAdvance = false
        var phase by mutableStateOf(WelcomePhase.Intro)
        val events = mutableListOf<WelcomeScreenIntent>()
        setContent {
            HbTheme {
                WelcomeContent(phase, {
                    events += it
                    if (it == WelcomeScreenIntent.Skip) phase = WelcomePhase.Ready
                })
            }
        }
        mainClock.advanceTimeBy(64)
        onNodeWithTag("welcome-skip").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(64)
        runOnIdle { assertEquals(listOf<WelcomeScreenIntent>(WelcomeScreenIntent.Skip), events) }
        onNodeWithTag("welcome-studio").assertIsEnabled()
        mainClock.advanceTimeBy(7000)
        assertEquals(listOf<WelcomeScreenIntent>(WelcomeScreenIntent.Skip), events)
    }

    @Test
    fun `reduced motion bypasses the six second wait`() = runSkikoComposeUiTest {
        var phase by mutableStateOf(WelcomePhase.Intro)
        setContent {
            HbTheme(motion = HbMotion(isReducedMotion = true)) {
                WelcomeContent(phase, { if (it == WelcomeScreenIntent.Skip) phase = WelcomePhase.Ready })
            }
        }
        onNodeWithTag("welcome-studio").assertIsEnabled()
        onNodeWithTag("welcome-skip").assertDoesNotExist()
    }

    @Test
    fun `compact screen with double font scale keeps both actions reachable`() = runSkikoComposeUiTest(
        size = Size(320f, 740f),
    ) {
        val events = mutableListOf<WelcomeScreenIntent>()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HbTheme { WelcomeContent(WelcomePhase.Ready, events::add) }
            }
        }
        onNodeWithTag("welcome-studio").performScrollTo().assertIsDisplayed()
        onNodeWithTag("welcome-toggles").performScrollTo().assertIsDisplayed().performClick()
        save("welcome-compact-large-text", captureToImage().toAwtImage())
        assertEquals(WelcomeScreenIntent.OpenToggles, events.last())
    }

    @Test
    fun `recreating the composition retains elapsed intro progress`() = runSkikoComposeUiTest {
        mainClock.autoAdvance = false
        var show by mutableStateOf(true)
        var phase by mutableStateOf(WelcomePhase.Intro)
        val events = mutableListOf<WelcomeScreenIntent>()
        setContent {
            val holder = rememberSaveableStateHolder()
            if (show) {
                holder.SaveableStateProvider("welcome") {
                    HbTheme {
                        WelcomeContent(phase, {
                            events += it
                            if (it == WelcomeScreenIntent.IntroFinished) phase = WelcomePhase.Ready
                        })
                    }
                }
            }
        }
        mainClock.advanceTimeBy(3000)
        runOnIdle { show = false }
        mainClock.advanceTimeBy(64)
        runOnIdle { show = true }
        mainClock.advanceTimeBy(64)
        assertFalse(events.contains(WelcomeScreenIntent.IntroFinished))
        mainClock.advanceTimeBy(3200)
        onNodeWithTag("welcome-studio").assertIsEnabled()
        assertEquals(1, events.count { it == WelcomeScreenIntent.IntroFinished })
    }

    @Test
    fun `system animation scale zero ends an in-flight intro`() {
        val scale = object : MotionDurationScale {
            override var scaleFactor = 1f
        }
        runSkikoComposeUiTest(effectContext = scale) {
            mainClock.autoAdvance = false
            var phase by mutableStateOf(WelcomePhase.Intro)
            setContent {
                HbTheme {
                    WelcomeContent(phase, {
                        if (it == WelcomeScreenIntent.IntroFinished || it == WelcomeScreenIntent.Skip) {
                            phase = WelcomePhase.Ready
                        }
                    })
                }
            }
            mainClock.advanceTimeBy(2000)
            runOnIdle { scale.scaleFactor = 0f }
            mainClock.advanceTimeBy(64)
            onNodeWithTag("welcome-studio").assertIsEnabled()
        }
    }

    @Test
    fun `tab and enter reach the second destination`() = runSkikoComposeUiTest {
        val events = mutableListOf<WelcomeScreenIntent>()
        setContent { HbTheme { WelcomeContent(WelcomePhase.Ready, events::add) } }
        mainClock.advanceTimeBy(64)
        onNodeWithTag("welcome-studio").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("welcome-toggles").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf<WelcomeScreenIntent>(WelcomeScreenIntent.OpenToggles), events)
    }

    private fun save(name: String, image: java.awt.image.BufferedImage) {
        val file = File("build/reports/welcome/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
