package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbStudioDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Material touch-density previews exercise the real screen without claiming device/emulator coverage. */
@OptIn(ExperimentalTestApi::class)
class AiStudioMobilePreviewUiTest {
    @Test
    fun `mobile previews retain large touch controls in both themes`() {
        for (isDark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(390f, 844f)) {
                var state by mutableStateOf(desktopAuditWorkspace(isEmpty = true))
                var addLabel = ""
                setContent {
                    addLabel = stringResource(Res.string.composer_add)
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        HbTheme(
                            darkTheme = isDark,
                            platformUi = PlatformUi.Material,
                            dimensions = HbDimensions(),
                            studioDimensions = HbStudioDimensions.Mobile,
                        ) {
                            AiStudioContent(
                                state,
                                { intent ->
                                    if (intent is AiStudioScreenIntent.SetDrawerOpen) {
                                        state = state.copy(sidebar = state.sidebar.copy(isDrawerOpen = intent.isOpen))
                                    }
                                },
                                StudioExits(onBack = {}, onOpenToggles = {}),
                            )
                        }
                    }
                }
                settleAudit()
                val add = onNodeWithContentDescription(addLabel).fetchSemanticsNode().boundsInRoot
                assertTrue(add.width >= 48f && add.height >= 48f, "Mobile add control retains its 48px touch target")
                val project = onNodeWithTag("project-chip-0").fetchSemanticsNode().boundsInRoot
                val model = onNodeWithTag("model-chip").fetchSemanticsNode().boundsInRoot
                assertTrue(project.bottom <= model.top, "Touch preference groups have separate rows without overlap")
                val theme = if (isDark) "dark" else "light"
                ImageIO.write(
                    captureToImage().toAwtImage(),
                    "png",
                    File(desktopAuditDirectory(), "mobile-$theme-empty.png"),
                )
                onNodeWithTag("pane-open-sidebar").performClick()
                settleAudit()
                val row = onNodeWithTag("session-audit-0").fetchSemanticsNode().boundsInRoot
                assertTrue(row.height >= 48f, "Mobile navigation retains its 48px touch target")
                ImageIO.write(
                    captureToImage().toAwtImage(),
                    "png",
                    File(desktopAuditDirectory(), "mobile-$theme-drawer.png"),
                )
            }
        }
    }
}
