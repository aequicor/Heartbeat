package io.aequicor.heartbeat.feature.projectconfig.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigScreenState
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.StorageUi
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProjectConfigStorageUiTest {
    @Test
    fun `storage settings render in compact and wide layouts in both themes`() {
        for (width in listOf(420f, 1280f)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 900f)) {
                    setContent {
                        HbTheme(darkTheme = dark) {
                            ProjectConfigContent(
                                ProjectConfigScreenState(
                                    phase = PhaseUi.Ready,
                                    projects = persistentListOf(
                                        ProjectUi(
                                            key = "project",
                                            name = "Heartbeat",
                                            hasFolder = true,
                                            isSyncing = false,
                                            isUnreadable = false,
                                            units = persistentListOf(),
                                            storage = StorageUi.Project,
                                        ),
                                    ),
                                ),
                                onIntent = {},
                                onBack = null,
                            )
                        }
                    }
                    val storage = onNodeWithTag("project-config-storage")
                    val bounds = storage.fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width)
                    val image = captureToImage().toAwtImage()
                    val preview = File(System.getProperty("java.io.tmpdir"), "project-config-storage-$width-$dark.png")
                    check(ImageIO.write(image, "png", preview))
                }
            }
        }
    }
}
