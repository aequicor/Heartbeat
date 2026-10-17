package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppQuickActionsUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ButtonStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.QuickActionUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.inertQuickAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.NoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceInputBinding
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class QuickActionsUiTest {
    private val previousLocale = Locale.getDefault()
    private val authority = SurfaceInputBinding()
    private val start = quick("start", "Start stage")
    private val review = quick("review", "Review stage", style = ButtonStyleUi.Primary)
    private val finish = quick("finish", "Finish process")

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `on a wide row every action is a button and pressing one sends it with the authority`() =
        runSkikoComposeUiTest(size = Size(900f, 200f)) {
            val intents = mutableListOf<PlaceIntent>()
            val state = ui(start, review, finish)
            setContent { HbTheme(darkTheme = false) { QuickActionsView(state, { intents += it }) } }

            onNodeWithText("Start stage").assertIsDisplayed()
            onNodeWithText("Review stage").assertIsDisplayed()
            onNodeWithText("Finish process").assertIsDisplayed()
            onNodeWithContentDescription("More quick actions").assertDoesNotExist()
            onNodeWithText("Review stage").performClick()

            assertEquals(listOf<PlaceIntent>(PlaceIntent.RunQuickAction(review, authority)), intents)
        }

    @Test
    fun `on a narrow row the actions that do not fit move into the menu in declared order`() =
        runSkikoComposeUiTest(size = Size(190f, 400f)) {
            val intents = mutableListOf<PlaceIntent>()
            val state = ui(start, review, finish)
            setContent { HbTheme(darkTheme = true) { QuickActionsView(state, { intents += it }) } }

            onNodeWithText("Start stage").assertIsDisplayed()
            onNodeWithText("Review stage").assertDoesNotExist()
            onNodeWithText("Finish process").assertDoesNotExist()
            onNodeWithContentDescription("More quick actions").performClick()
            onNodeWithText("Review stage").assertIsDisplayed()
            onNodeWithText("Finish process").performClick()

            assertEquals(listOf<PlaceIntent>(PlaceIntent.RunQuickAction(finish, authority)), intents)
        }

    @Test
    fun `the overflow keeps as many leading actions as fit and menu actions still run`() =
        runSkikoComposeUiTest(size = Size(270f, 400f)) {
            val intents = mutableListOf<PlaceIntent>()
            val state = ui(start, review, finish)
            setContent { HbTheme(darkTheme = false) { QuickActionsView(state, { intents += it }) } }

            onNodeWithText("Start stage").assertIsDisplayed()
            onNodeWithText("Review stage").assertIsDisplayed()
            onNodeWithText("Finish process").assertDoesNotExist()
            onNodeWithContentDescription("More quick actions").performClick()
            onNodeWithText("Finish process").assertIsDisplayed()
            onNodeWithText("Start stage").assertIsDisplayed()
        }

    @Test
    fun `an action with a confirmation asks first and runs only once confirmed`() =
        runSkikoComposeUiTest(size = Size(600f, 400f)) {
            val intents = mutableListOf<PlaceIntent>()
            val risky = quick("drop", "Drop stage", ButtonStyleUi.Danger, confirm = "Drop stage two?")
            setContent { HbTheme(darkTheme = false) { QuickActionsView(ui(risky), { intents += it }) } }

            onNodeWithText("Drop stage").performClick()
            onNodeWithText("Drop stage two?").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertEquals(emptyList(), intents)

            onNodeWithText("Drop stage").performClick()
            onNodeWithText("Drop stage two?").assertIsDisplayed()
            onAllNodes(hasText("Drop stage") and hasClickAction()).onLast().performClick()
            assertEquals(listOf<PlaceIntent>(PlaceIntent.RunQuickAction(risky, authority)), intents)
        }

    @Test
    fun `a disabled busy or unavailable action cannot be pressed`() = runSkikoComposeUiTest(size = Size(900f, 200f)) {
        val state = ui(
            quick("a", "Alpha", isEnabled = false),
            quick("b", "Beta", isBusy = true),
            quick("c", "Gamma"),
        )
        setContent { HbTheme(darkTheme = false) { QuickActionsView(state, {}) } }

        onNodeWithText("Alpha").assertIsNotEnabled()
        onNodeWithText("Beta").assertIsNotEnabled()
        onNodeWithContentDescription("Running").assertIsDisplayed()
        onNodeWithText("Gamma").assertIsEnabled()
    }

    @Test
    fun `an unavailable owner disables every action`() = runSkikoComposeUiTest(size = Size(900f, 200f)) {
        val state = ui(start, review).copy(isAvailable = false)
        setContent { HbTheme(darkTheme = false) { QuickActionsView(state, {}) } }

        onNodeWithText("Start stage").assertIsNotEnabled()
        onNodeWithText("Review stage").assertIsNotEnabled()
    }

    @Test
    fun `an icon names the button by its label`() = runSkikoComposeUiTest(size = Size(600f, 200f)) {
        val intents = mutableListOf<PlaceIntent>()
        val play = quick("play", "Play stage", icon = "play")
        setContent { HbTheme(darkTheme = false) { QuickActionsView(ui(play), { intents += it }) } }

        onNodeWithText("Play stage").assertDoesNotExist()
        onNodeWithContentDescription("Play stage").performClick()
        assertEquals(listOf<PlaceIntent>(PlaceIntent.RunQuickAction(play, authority)), intents)
    }

    @Test
    fun `the notice of a failed action is shown above the row and can be dismissed`() =
        runSkikoComposeUiTest(size = Size(600f, 300f)) {
            val intents = mutableListOf<PlaceIntent>()
            val state = ui(start).copy(notice = NoticeUi.Message("Sign in to gh"))
            setContent { HbTheme(darkTheme = true) { QuickActionsView(state, { intents += it }) } }

            onNodeWithText("Sign in to gh").assertIsDisplayed()
            onNodeWithText("Start stage").assertIsDisplayed()
            onNodeWithText("Dismiss").performClick()

            assertEquals(listOf<PlaceIntent>(PlaceIntent.DismissQuickNotice(authority)), intents)
        }

    @Test
    fun `only a notice leaves no row and nothing at all leaves no space`() =
        runSkikoComposeUiTest(size = Size(600f, 300f)) {
            val state = AppQuickActionsUi(notice = NoticeUi.Unsupported, authority = authority)
            setContent {
                HbTheme(darkTheme = false) {
                    QuickActionsView(state, {})
                    QuickActionsView(AppQuickActionsUi(), {}, Modifier.testTag("empty"))
                }
            }

            onNodeWithText("This action is not available here").assertIsDisplayed()
            onNodeWithTag(QUICK_ACTIONS_TAG).assertDoesNotExist()
            onNodeWithTag("empty").assertDoesNotExist()
        }

    @Test
    fun `the composer slot draws the row and a slot with nothing draws nothing`() =
        runSkikoComposeUiTest(size = Size(900f, 300f)) {
            val state = PlaceState(quickActions = ui(start))
            setContent {
                HbTheme(darkTheme = false) { PlaceView(state, persistentMapOf(), {}, isPanel = false) }
            }

            onNodeWithTag(QUICK_ACTIONS_TAG).assertIsDisplayed()
            onNodeWithText("Start stage").assertIsDisplayed()
        }

    private fun ui(vararg actions: QuickActionUi) =
        AppQuickActionsUi(persistentListOf(*actions), isAvailable = true, authority = authority)

    private fun quick(
        id: String,
        label: String,
        style: ButtonStyleUi = ButtonStyleUi.Secondary,
        icon: String? = null,
        isEnabled: Boolean = true,
        isBusy: Boolean = false,
        confirm: String? = null,
    ) = inertQuickAction(id, label, style, icon, isEnabled, isBusy, confirm)
}
