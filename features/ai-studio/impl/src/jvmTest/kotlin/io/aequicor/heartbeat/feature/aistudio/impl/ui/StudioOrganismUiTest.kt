package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.InputSupportUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismActionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismPermissionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PermissionOptionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionTreeUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioModelOptions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionKindUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionStateUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_abort
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_zygote
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_tree_activity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StudioOrganismUiTest {
    @Test
    fun `the plus menu of a new chat offers the organism mode`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<AiStudioScreenIntent>()
        val pane = PaneUi(0)
        val state = AiStudioScreenState(panes = persistentListOf(pane), models = StudioModelOptions)
        var addLabel = ""
        var modeLabel = ""
        var isOffered by mutableStateOf(true)
        setContent {
            addLabel = stringResource(Res.string.composer_add)
            modeLabel = stringResource(Res.string.organism_mode)
            HbTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    StudioComposer(
                        state.copy(isOrganismEnabled = isOffered).paneContent(pane),
                        { events += it },
                        isCompact = false,
                    )
                }
            }
        }
        onNodeWithContentDescription(addLabel).performClick()
        onNodeWithText(modeLabel).performClick()
        runOnIdle { assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.SelectOrganism(0, true)), events) }
        isOffered = false
        waitForIdle()
        onNodeWithContentDescription(addLabel).performClick()
        onAllNodesWithText(modeLabel).assertCountEquals(0)
    }

    @Test
    fun `a chosen organism mode is shown and can be switched off before the first message`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val pane = PaneUi(0, isOrganism = true)
            val state = AiStudioScreenState(
                panes = persistentListOf(pane),
                models = StudioModelOptions,
                isOrganismEnabled = true,
            )
            setContent {
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        StudioComposer(state.paneContent(pane), { events += it }, isCompact = false)
                    }
                }
            }
            onNodeWithTag("organism-mode-0").performClick()
            runOnIdle {
                assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.SelectOrganism(0, false)), events)
            }
        }

    @Test
    fun `an organism chat switches sub-sessions and stops its organism from the header`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            var zygote = ""
            var abort = ""
            setContent {
                zygote = stringResource(Res.string.organism_zygote)
                abort = stringResource(Res.string.organism_abort)
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                        OrganismSwitcher(organismState().paneContent(organismPane), { events += it })
                    }
                }
            }
            onNodeWithTag("organism-switcher-0").performClick()
            onNodeWithText("c1 · tests").performClick()
            onNodeWithTag("organism-switcher-0").performClick()
            onNodeWithText(abort).performClick()
            runOnIdle {
                assertEquals(
                    listOf<AiStudioScreenIntent>(
                        AiStudioScreenIntent.SelectSubSession("chat", "c1"),
                        AiStudioScreenIntent.ControlOrganism("chat", OrganismActionUi.Abort),
                    ),
                    events,
                )
                assertTrue(zygote.isNotBlank())
            }
        }

    @Test
    fun `a cell's permission request is answered from the pane`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        val events = mutableListOf<AiStudioScreenIntent>()
        setContent {
            HbTheme(darkTheme = false) {
                Column {
                    OrganismNotices(
                        organismState().paneContent(organismPane),
                        HbTheme.dimensions.toolPayloadMaxHeight,
                    ) {
                        events += it
                    }
                }
            }
        }
        onNodeWithTag("organism-status-0").assertDoesNotExist()
        // What the cell asks to run is shown whole, never cut to a few lines.
        onNodeWithTag("permission-organism-c1-p1-description").assertTextEquals(COMMAND)
        onNodeWithTag("organism-permission-c1-p1-allow").performClick()
        runOnIdle {
            assertEquals(
                listOf<AiStudioScreenIntent>(AiStudioScreenIntent.DecideOrganism("chat", "c1", "t1", "p1", "allow")),
                events,
            )
        }
    }

    @Test
    fun `a chat whose organism is out of view says so`() = runSkikoComposeUiTest(size = Size(900f, 700f)) {
        setContent {
            HbTheme(darkTheme = false) {
                Column {
                    val hidden = organismState().copy(organisms = persistentMapOf()).paneContent(organismPane)
                    OrganismNotices(hidden, HbTheme.dimensions.toolPayloadMaxHeight) {}
                }
            }
        }
        onNodeWithTag("organism-unavailable-0").assertExists()
    }

    @Test
    fun `an organism chat takes its goal again only while its organism is out of view`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            var send = ""
            var isInView by mutableStateOf(true)
            var isOrganicAiOn by mutableStateOf(true)
            val drafted = organismState().copy(drafts = persistentMapOf("chat" to "Build"))
            setContent {
                send = stringResource(Res.string.composer_send)
                val shown = if (isInView) drafted else drafted.copy(organisms = persistentMapOf())
                val state = shown.copy(isOrganismEnabled = isOrganicAiOn)
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        StudioComposer(state.paneContent(organismPane), {}, isCompact = false)
                    }
                }
            }
            onNodeWithContentDescription(send).assertIsNotEnabled()
            onNodeWithTag("organism-pinned-0").assertDoesNotExist()
            isInView = false
            waitForIdle()
            onNodeWithContentDescription(send).assertIsEnabled()
            // With organic AI off a goal could not be conceived, so the chat does not invite one.
            isOrganicAiOn = false
            waitForIdle()
            onNodeWithContentDescription(send).assertIsNotEnabled()
        }

    @Test
    fun `images can be attached and sent after choosing organism mode`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            var send = ""
            val file = AttachmentUi("image", "shape.png", "image/png", 10)
            val pane = PaneUi(0, isOrganism = true)
            val initial = AiStudioScreenState(panes = persistentListOf(pane), isOrganismEnabled = true)
            val support = InputSupportUi(mediaTypes = persistentListOf(file.mediaType))
            val state = initial.copy(
                models = persistentListOf(ModelUi("model", "Model", inputSupport = support)),
                settings = initial.settings.copy(modelId = "model"),
                drafts = persistentMapOf("pane:0" to "Build"),
                draftAttachments = persistentMapOf("pane:0" to persistentListOf(file)),
                isAttachmentsEnabled = true,
            )
            setContent {
                send = stringResource(Res.string.composer_send)
                HbTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        StudioComposer(state.paneContent(pane), {}, isCompact = false)
                    }
                }
            }
            onNodeWithTag("attachment-add-0").assertIsEnabled()
            onNodeWithContentDescription(send).assertIsEnabled()
        }

    @Test
    fun `activity badge explains the count and uncertainty without hover`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            var label = ""
            setContent {
                label = stringResource(Res.string.session_tree_activity)
                HbTheme(darkTheme = false) {
                    OrganismSwitcher(
                        AiStudioScreenState(
                            sessions = persistentListOf(
                                SessionUi("chat", "Chat", projectId = null, updatedAt = Instant.DISTANT_PAST),
                            ),
                            nativeTrees = persistentMapOf(
                                "chat" to SessionTreeUi(persistentListOf(), activeCount = 2, isActivityKnown = false),
                            ),
                        ).paneContent(organismPane),
                        {},
                    )
                }
            }
            onNodeWithContentDescription("$label: 2+").assertExists()
        }

    @Test
    fun `light organism permissions use questionnaire cards at compact and desktop widths`() {
        verifyPermissionCard(isDark = false, width = 420)
        verifyPermissionCard(isDark = false, width = 1280)
    }

    @Test
    fun `dark organism permissions use questionnaire cards at compact and desktop widths`() {
        verifyPermissionCard(isDark = true, width = 420)
        verifyPermissionCard(isDark = true, width = 1280)
    }

    private fun verifyPermissionCard(isDark: Boolean, width: Int) = runSkikoComposeUiTest(
        size = Size(width.toFloat(), 600f),
    ) {
        val events = mutableListOf<AiStudioScreenIntent>()
        val description = (1..80).joinToString("\n") { "Строка $it: полный текст новой инструкции для проекта." }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme(
                    darkTheme = isDark,
                    dimensions = HbDimensions.Desktop,
                    motion = HbMotion(isReducedMotion = true),
                ) {
                    StudioPaneView(
                        organismState(description).paneContent(organismPane),
                        events::add,
                        PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = width < 600),
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val card = onNodeWithTag("organism-permission-c1-p1").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val accent = onNodeWithTag("question-accent-organism-c1-p1").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        onNodeWithTag("question-attention-organism-c1-p1").assertIsDisplayed()
        onNodeWithTag("permission-organism-c1-p1-description").assertTextEquals(description)
        onNodeWithTag("permission-organism-c1-p1-more").assertIsDisplayed()
        val allow = onNodeWithTag("organism-permission-c1-p1-allow").assertIsDisplayed()
        val deny = onNodeWithTag("organism-permission-c1-p1-deny").assertIsDisplayed()
        listOf(allow, deny).forEach { action ->
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue(card.contains(bounds.topLeft) && card.contains(bounds.bottomRight))
        }
        assertEquals(allow.fetchSemanticsNode().boundsInRoot.top, deny.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(card.left >= 0 && card.right <= width && card.top >= 0 && card.bottom <= 600)
        assertTrue(card.width <= HbDimensions.Desktop.messageMaxWidth.value)
        assertEquals(width / 2f, card.center.x)
        val image = captureToImage().toAwtImage()
        val colors = HbColors.forHost(isDark = isDark, isDesktop = true).forQuestionnaire()
        assertEquals(colors.surface.toArgb(), image.getRGB((accent.right - 4).toInt(), card.center.y.toInt()))
        val directory = File("build/previews")
        check(directory.isDirectory || directory.mkdirs())
        check(ImageIO.write(image, "png", File(directory, "organism-permission-$isDark-$width.png")))

        val text = onNodeWithTag("permission-organism-c1-p1-description")
        val top = text.fetchSemanticsNode().positionInRoot.y
        onNodeWithTag("permission-organism-c1-p1-scroll").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("permission-organism-c1-p1-scroll").performKeyInput { pressKey(Key.PageDown) }
        waitForIdle()
        assertTrue(text.fetchSemanticsNode().positionInRoot.y < top)
        allow.performSemanticsAction(SemanticsActions.RequestFocus)
        allow.performKeyInput { pressKey(Key.Tab) }
        deny.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals(
                listOf(AiStudioScreenIntent.DecideOrganism("chat", "c1", "t1", "p1", "deny")),
                events.filterIsInstance<AiStudioScreenIntent.DecideOrganism>(),
            )
        }
    }

    private val organismPane = PaneUi(0, sessionId = "chat")

    private fun organismState(description: String = COMMAND) = AiStudioScreenState(
        panes = persistentListOf(organismPane),
        models = StudioModelOptions,
        sessions = persistentListOf(
            SessionUi(
                "chat",
                "Build",
                null,
                Instant.fromEpochMilliseconds(0),
                isContinuable = false,
                isOrganism = true,
            ),
        ),
        organisms = persistentMapOf(
            "chat" to OrganismUi(
                OrganismStatusUi.Developing,
                persistentListOf(
                    SubSessionUi(
                        "zygote",
                        SubSessionKindUi.Zygote,
                        "zygote",
                        SubSessionStateUi.Resting,
                        isViewable = true,
                    ),
                    SubSessionUi(
                        "c1",
                        SubSessionKindUi.Cell,
                        "tests",
                        SubSessionStateUi.AwaitingUser,
                        isViewable = true,
                    ),
                    SubSessionUi("k1", SubSessionKindUi.Complaint, "k1", SubSessionStateUi.Judging, subject = "c1"),
                ),
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
                        description = description,
                    ),
                ),
            ),
        ),
    )

    private companion object {
        val COMMAND = (1..12).joinToString("\n") { "./gradlew :module$it:check" }
    }
}
