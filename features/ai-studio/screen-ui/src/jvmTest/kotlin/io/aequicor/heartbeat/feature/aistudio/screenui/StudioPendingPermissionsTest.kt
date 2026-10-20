package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismPermissionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PermissionOptionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionTreeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionKindUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionStateUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

@OptIn(ExperimentalTestApi::class)
class StudioPendingPermissionsTest {
    @Test
    fun `background workflow permission is reachable without a chat record or pane`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            setContent {
                HbTheme(darkTheme = false) {
                    val state = organismState().copy(sessions = persistentListOf(), panes = persistentListOf())
                    StudioPendingPermissions(state, emptySet(), { events += it })
                }
            }
            onNodeWithTag("permission-organism-c1-p1-description").assertTextEquals(COMMAND)
            onNodeWithTag("organism-permission-c1-p1-allow").performClick()
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(StudioIntent.DecideOrganism("chat", "c1", "t1", "p1", "allow")),
                    events,
                )
            }
        }

    @Test
    fun `visible organism permissions are not duplicated in the background panel`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            setContent {
                HbTheme(darkTheme = true) {
                    StudioPendingPermissions(organismState(), setOf("chat"), {})
                }
            }
            onNodeWithTag("studio-pending-permissions").assertDoesNotExist()
        }

    @Test
    fun `projection follows actual notices across child split compact and embedded views`() {
        val root = organismState().copy(
            panes = persistentListOf(PaneUi(0, sessionId = "chat"), PaneUi(1, sessionId = "other")),
            focusedPaneId = 1,
            sessions = persistentListOf(SessionUi("chat", "Build", null, Instant.DISTANT_PAST, isOrganism = true)),
        )
        assertEquals(setOf("chat"), root.visiblePermissionChats(true, false, false))
        assertEquals(emptySet(), root.visiblePermissionChats(true, true, false))
        assertEquals(emptySet(), root.visiblePermissionChats(false, false, false))
        assertEquals(emptySet(), root.visiblePermissionChats(true, false, true))
        val child = root.copy(
            subSessions = persistentMapOf("chat" to "child"),
            nativeTrees = persistentMapOf(
                "chat" to SessionTreeUi(
                    persistentListOf(SubSessionUi("child", SubSessionKindUi.Agent, "Child", SubSessionStateUi.Resting)),
                    0,
                    true,
                ),
            ),
        )
        assertEquals(emptySet(), child.visiblePermissionChats(true, false, false))
        assertEquals(emptySet(), root.copy(sessions = persistentListOf()).visiblePermissionChats(true, false, false))
    }

    @Test
    fun `compact workspace footer keeps the last background decision reachable`() =
        runSkikoComposeUiTest(size = Size(420f, 360f)) {
            val events = mutableListOf<StudioIntent>()
            val first = organismState().organisms.getValue("chat")
            val second = first.copy(permissions = persistentListOf(first.permissions.single().copy(cell = "c2")))
            val state = organismState().copy(
                phase = StudioPhase.Ready,
                panes = persistentListOf(PaneUi(0)),
                organisms = persistentMapOf("chat" to first, "second" to second),
            )
            setContent {
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, { events += it }, StudioExits())
                }
            }
            onNodeWithTag("organism-permission-c2-p1-allow").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(StudioIntent.DecideOrganism("second", "c2", "t1", "p1", "allow")),
                    events,
                )
            }
        }

    private fun organismState() = AiStudioScreenState(
        organisms = persistentMapOf(
            "chat" to OrganismUi(
                OrganismStatusUi.Developing,
                persistentListOf(),
                persistentListOf(
                    OrganismPermissionUi(
                        "c1",
                        "tests",
                        "t1",
                        "p1",
                        "Run gradle",
                        persistentListOf(
                            PermissionOptionUi("allow", "Разрешить"),
                            PermissionOptionUi("deny", "Запретить"),
                        ),
                        description = COMMAND,
                    ),
                ),
            ),
        ),
    )

    private companion object {
        val COMMAND = (1..12).joinToString("\n") { "./gradlew :module$it:check" }
    }
}
