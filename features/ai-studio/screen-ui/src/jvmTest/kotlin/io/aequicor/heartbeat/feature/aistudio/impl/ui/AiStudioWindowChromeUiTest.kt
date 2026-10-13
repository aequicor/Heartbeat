package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.HbWindowChrome
import io.aequicor.heartbeat.ds.components.HbWindowChromeProvider
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioPhase
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real screen geometry with native caption metrics; OS rendering and snap still need an on-device check. */
@OptIn(ExperimentalTestApi::class)
class AiStudioWindowChromeUiTest {
    @Test
    fun `desktop screens keep caption controls clear across widths themes and split panes`() {
        for (width in listOf(420, 900, 1280)) {
            for (dark in listOf(false, true)) {
                for (mac in listOf(false, true)) render(width, dark, mac)
            }
        }
    }

    private fun render(width: Int, dark: Boolean, mac: Boolean) =
        runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
            val base = desktopAuditWorkspace(isEmpty = false)
            val state = if (width == 1280) base.copy(panes = persistentListOf(base.panes[0], PaneUi(1))) else base
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) {
                        HbWindowChromeProvider(
                            HbWindowChrome(44.dp, if (mac) 96.dp else 0.dp, if (mac) 0.dp else 138.dp),
                            Modifier.fillMaxSize(),
                        ) {
                            AiStudioContent(state, {}, StudioExits(onBack = {}, onOpenToggles = {}))
                        }
                    }
                }
            }
            waitForIdle()
            assertCaptionBounds(width, mac)
            saveCaption("$width-$dark-$mac")
            onNodeWithTag("pane-menu-0").performClick()
            waitForIdle()
            saveCaption("$width-$dark-$mac-menu")
        }

    @Test
    fun `loading and error keep a native drag area and actionable retry in both themes`() {
        for (dark in listOf(false, true)) {
            for (phase in listOf(StudioPhase.Loading, StudioPhase.Error)) renderStatus(dark, phase)
        }
    }

    private fun renderStatus(dark: Boolean, phase: StudioPhase) = runSkikoComposeUiTest(size = Size(420f, 800f)) {
        val hitTests = mutableListOf<Boolean>()
        var retries = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme(darkTheme = dark, dimensions = HbDimensions.Desktop) {
                    HbWindowChromeProvider(
                        HbWindowChrome(44.dp, 96.dp),
                        Modifier.fillMaxSize(),
                        onNativeHitTest = { hitTests += it },
                    ) {
                        AiStudioContent(
                            desktopAuditWorkspace(isEmpty = true).copy(phase = phase),
                            { retries++ },
                            StudioExits(onBack = {}, onOpenToggles = {}),
                        )
                    }
                }
            }
        }
        onNodeWithTag("ai-studio").performMouseInput {
            moveTo(Offset(220f, 20f))
            press()
            release()
        }
        assertEquals(false, hitTests.last(), "Status screens must remain draggable")
        if (phase == StudioPhase.Error) {
            onNodeWithTag("studio-retry").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            assertEquals(1, retries)
            assertEquals(true, hitTests.last())
        }
        saveCaption("420-$dark-$phase")
    }

    private fun SkikoComposeUiTest.saveCaption(name: String) {
        val directory = File("build/reports/window-chrome").apply { mkdirs() }
        check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png")))
    }

    private fun SkikoComposeUiTest.assertCaptionBounds(width: Int, mac: Boolean) {
        val header = onNodeWithTag("pane-header-0").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, header.top)
        assertEquals(44f, header.height)
        if (width > 420) {
            val create = onNodeWithTag("sidebar-new-session").fetchSemanticsNode().boundsInRoot
            assertTrue(create.top >= 0f && create.bottom <= 44f)
            if (mac) assertTrue(create.left >= 96f)
        } else {
            val menu = onNodeWithTag("pane-open-sidebar").fetchSemanticsNode().boundsInRoot
            if (mac) assertTrue(menu.left >= 96f)
            assertTrue(menu.right <= width - if (mac) 0f else 138f)
        }
    }
}
