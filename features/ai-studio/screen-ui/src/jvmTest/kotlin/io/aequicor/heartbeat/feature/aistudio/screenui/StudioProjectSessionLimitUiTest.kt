package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_show_fewer_sessions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_show_more_sessions
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SidebarUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.reduce
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** The project limit and detached section remain operable in both sidebar layouts and themes. */
@OptIn(ExperimentalTestApi::class)
class StudioProjectSessionLimitUiTest {
    @Test
    fun `project sessions expand and collapse while no project keeps independent keyboard disclosure`() {
        for (isDark in listOf(false, true)) {
            for (width in listOf(1280f, 420f)) {
                runSkikoComposeUiTest(size = Size(width, 800f)) {
                    var state by mutableStateOf(projectLimitWorkspace(projectSessionCount = 7, hasDetached = true))
                    var moreLabel = ""
                    var fewerLabel = ""
                    setContent {
                        moreLabel = stringResource(Res.string.sidebar_show_more_sessions, 2)
                        fewerLabel = stringResource(Res.string.sidebar_show_fewer_sessions)
                        HbTheme(
                            darkTheme = isDark,
                            dimensions = HbDimensions.Desktop,
                            motion = HbMotion(isReducedMotion = true),
                        ) {
                            AiStudioContent(state, { intent ->
                                if (intent is StudioIntent.Sidebar) {
                                    state = state.copy(sidebar = state.sidebar.reduce(intent))
                                }
                            }, projectLimitExits)
                        }
                    }
                    for (index in 1..5) {
                        onNodeWithTag("session-project-session-$index").assertIsDisplayed()
                    }
                    onNodeWithTag("session-project-session-6").assertDoesNotExist()
                    onNodeWithTag("session-project-session-7").assertDoesNotExist()
                    onNodeWithTag("sidebar-section-recent").assertDoesNotExist()
                    val detachedHeader = onNodeWithTag("sidebar-section-no-project")
                    val detachedSession = onNodeWithTag("session-detached-session")
                    detachedHeader.assertIsDisplayed()
                    detachedSession.assertIsDisplayed()
                    val toggle = onNodeWithTag("project-limited-project-sessions-toggle")
                    toggle.assertIsDisplayed().assertTextContains(moreLabel)
                    saveProjectLimitImage("collapsed-${width.toInt()}-$isDark")

                    toggle.performClick().assertTextContains(fewerLabel)
                    onNodeWithTag("session-project-session-6").assertIsDisplayed()
                    onNodeWithTag("session-project-session-7").assertIsDisplayed()
                    detachedSession.assertIsDisplayed()
                    saveProjectLimitImage("expanded-${width.toInt()}-$isDark")

                    toggle.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.Tab) }
                    detachedHeader.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
                    detachedSession.assertDoesNotExist()
                    onNodeWithTag("session-project-session-7").assertIsDisplayed()
                    detachedHeader.performKeyInput { pressKey(Key.Enter) }
                    detachedSession.assertIsDisplayed()
                    toggle.requestFocus().performKeyInput { pressKey(Key.Enter) }
                    toggle.assertTextContains(moreLabel)
                    onNodeWithTag("session-project-session-5").assertIsDisplayed()
                    onNodeWithTag("session-project-session-6").assertDoesNotExist()
                    onNodeWithTag("session-project-session-7").assertDoesNotExist()
                    detachedSession.assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun `five project sessions have no disclosure and no empty detached section`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            setContent {
                HbTheme(darkTheme = false) {
                    AiStudioContent(
                        projectLimitWorkspace(projectSessionCount = 5, hasDetached = false),
                        {},
                        projectLimitExits,
                    )
                }
            }
            for (index in 1..5) {
                onNodeWithTag("session-project-session-$index").assertIsDisplayed()
            }
            onNodeWithTag("project-limited-project-sessions-toggle").assertDoesNotExist()
            onNodeWithTag("sidebar-section-no-project").assertDoesNotExist()
            onNodeWithTag("sidebar-section-recent").assertDoesNotExist()
        }
}

private fun projectLimitWorkspace(projectSessionCount: Int, hasDetached: Boolean): AiStudioScreenState {
    val time = Instant.parse("2026-10-14T09:00:00Z")
    val sessions = (1..projectSessionCount).map { index ->
        SessionUi(
            id = "project-session-$index",
            title = "Сессия $index: работа над проектом",
            projectId = "limited-project",
            updatedAt = time - index.minutes,
        )
    }
    val detached = if (hasDetached) {
        listOf(SessionUi("detached-session", "Отдельная беседа", null, time))
    } else {
        emptyList()
    }
    return desktopAuditWorkspace(isEmpty = true).copy(
        projects = persistentListOf(ProjectUi("limited-project", "Heartbeat", EnvironmentUi.Local, "master")),
        sessions = (sessions + detached).toImmutableList(),
        sidebar = SidebarUi(isDrawerOpen = true),
    )
}

@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.saveProjectLimitImage(name: String) {
    val directory = File("build/screenshots/project-session-limit").apply { mkdirs() }
    check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png")))
}

private val projectLimitExits = StudioExits(onBack = {}, onOpenToggles = {})
