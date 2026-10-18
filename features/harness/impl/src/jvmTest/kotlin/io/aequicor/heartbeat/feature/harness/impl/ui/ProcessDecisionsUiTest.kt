package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessDecisionStatusUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessDecisionUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessDecisionsUiIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessDecisionsUiState
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProcessDecisionsUiTest {
    private val row = ProcessDecisionUi(
        id = "decision",
        revision = 7,
        status = ProcessDecisionStatusUi.Pending,
        repository = "github.com/owner/repository",
        workspace = "project-worktree",
        operation = "Merge",
        subject = "a".repeat(40),
        process = "development",
        stage = "merge",
        attempt = 2,
        inputRevision = 4,
        expiresAt = "2026-10-19T09:00:00Z",
        timeoutMillis = 60_000,
        words = "[\"gh\",\"pr\",\"merge\",\"27\",\"--match-head-commit\",\"${"a".repeat(40)}\"]",
    )

    @Test
    fun `native decisions remain visible keyboard accessible and fenced across widths and themes`() {
        for (width in listOf(1280, 420)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 900f)) {
                    val intents = mutableListOf<ProcessDecisionsUiIntent>()
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = dark) {
                                HbColumn(Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop)) {
                                    ProcessDecisionsContent(
                                        ProcessDecisionsUiState(persistentListOf(row)),
                                        intents::add,
                                    )
                                }
                            }
                        }
                    }
                    onNodeWithTag("process-decision-allow-decision").assertIsDisplayed()
                    onNodeWithTag("process-decisions").performKeyInput { pressKey(Key.Tab) }
                    onNodeWithTag("process-decision-allow-decision").assertIsFocused()
                        .performKeyInput { pressKey(Key.Spacebar) }
                    onNodeWithTag("process-decision-deny-decision").performClick()
                    runOnIdle {
                        assertEquals<List<ProcessDecisionsUiIntent>>(
                            listOf(
                                ProcessDecisionsUiIntent.Answer("decision", 7, true),
                                ProcessDecisionsUiIntent.Answer("decision", 7, false),
                            ),
                            intents,
                        )
                    }
                    val file = File("build/reports/snapshots/process-decisions-$width-$dark.png")
                    file.parentFile.mkdirs()
                    assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", file))
                }
            }
        }
    }

    @Test
    fun `expired requests cannot offer allow or deny controls`() = runSkikoComposeUiTest {
        setContent {
            HbTheme {
                ProcessDecisionsContent(
                    ProcessDecisionsUiState(persistentListOf(row.copy(status = ProcessDecisionStatusUi.Expired))),
                    {},
                )
            }
        }
        onNodeWithTag("process-decision-allow-decision").assertDoesNotExist()
        onNodeWithTag("process-decision-deny-decision").assertDoesNotExist()
    }

    @Test
    fun `admitted commands cannot offer a misleading revoke control`() = runSkikoComposeUiTest {
        setContent {
            HbTheme {
                ProcessDecisionsContent(
                    ProcessDecisionsUiState(persistentListOf(row.copy(status = ProcessDecisionStatusUi.Executing))),
                    {},
                )
            }
        }
        onNodeWithTag("process-decision-allow-decision").assertDoesNotExist()
        onNodeWithTag("process-decision-revoke-decision").assertDoesNotExist()
    }
}
