package io.aequicor.heartbeat.feature.questionnaire.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.ChoiceUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class QuestionnaireUiTest {
    private val state = QuestionnaireScreenState(
        persistentListOf(
            QuestionUi(
                "pick",
                "Which?",
                "Details",
                QuestionKindUi.Choice(persistentListOf(ChoiceUi("a", "A"), ChoiceUi("b", "B")), isMultiple = false),
                isSkippable = true,
                isSubmitting = false,
                selected = persistentSetOf("b"),
            ),
            QuestionUi("go", "Go?", null, QuestionKindUi.Confirm("Yes", "No"), false, false),
            QuestionUi("name", "Name", null, QuestionKindUi.Text(null, false), false, false),
        ),
    )

    @Test
    fun `choices confirmations and text are forwarded as intents`() = runSkikoComposeUiTest {
        val events = mutableListOf<QuestionnaireScreenIntent>()
        setContent { HbTheme { QuestionnaireScreen(state, { events += it }) } }
        onNodeWithTag("question-choice-pick-a").performClick()
        onNodeWithTag("question-submit-pick").assertIsEnabled().performClick()
        onNodeWithTag("question-skip-pick").performClick()
        onNodeWithTag("question-yes-go").performClick()
        onNodeWithTag("question-submit-name").assertIsNotEnabled()
        onNodeWithTag("question-text-name").performTextInput("x")
        assertEquals(
            listOf(
                QuestionnaireScreenIntent.ToggleChoice("pick", "a"),
                QuestionnaireScreenIntent.Submit("pick"),
                QuestionnaireScreenIntent.Skip("pick"),
                QuestionnaireScreenIntent.Confirm("go", true),
                QuestionnaireScreenIntent.TextChanged("name", "x"),
            ),
            events,
        )
    }

    @Test
    fun `light desktop questionnaire keeps long Russian actions visible at compact width`() {
        verifyQuestionnaireLayout(isDark = false, width = 420)
    }

    @Test
    fun `dark desktop questionnaire keeps long Russian actions visible at compact width`() {
        verifyQuestionnaireLayout(isDark = true, width = 420)
    }

    @Test
    fun `light desktop questionnaire uses the oak treatment at expanded width`() {
        verifyQuestionnaireLayout(isDark = false, width = 1280)
    }

    @Test
    fun `dark desktop questionnaire uses the oak treatment at expanded width`() {
        verifyQuestionnaireLayout(isDark = true, width = 1280)
    }

    @Test
    fun `medium desktop questionnaire remains readable in both themes`() {
        verifyQuestionnaireLayout(isDark = false, width = 900)
        verifyQuestionnaireLayout(isDark = true, width = 900)
    }

    @Test
    fun `reduced motion keeps the attention accent static`() = runSkikoComposeUiTest(size = Size(420f, 480f)) {
        mainClock.autoAdvance = false
        setContent {
            QuestionnaireTestHost(
                QuestionnaireScreenState(persistentListOf(VisualState.questions.first())),
                isDark = false,
                motion = HbMotion(isReducedMotion = true),
            )
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val bounds = onNodeWithTag("question-accent-pick").fetchSemanticsNode().boundsInRoot
        val initial = accentPixels(captureToImage().toAwtImage(), bounds)
        mainClock.advanceTimeBy(3000)
        val settled = captureToImage().toAwtImage()
        assertTrue(
            initial.contentEquals(accentPixels(settled, bounds)),
            "Reduced motion must retain a static attention accent",
        )
        saveQuestionnairePreview("reduced-motion", settled)
    }

    @Test
    fun `entrance accent settles and editing an answer does not restart it`() = runSkikoComposeUiTest(
        size = Size(420f, 480f),
    ) {
        var question by mutableStateOf(VisualState.questions.first())
        mainClock.autoAdvance = false
        setContent {
            QuestionnaireTestHost(QuestionnaireScreenState(persistentListOf(question)), isDark = true)
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(120)
        val bounds = onNodeWithTag("question-accent-pick").fetchSemanticsNode().boundsInRoot
        val entrance = accentPixels(captureToImage().toAwtImage(), bounds)
        mainClock.advanceTimeBy(3000)
        val restingBounds = onNodeWithTag("question-accent-pick").fetchSemanticsNode().boundsInRoot
        val resting = accentPixels(captureToImage().toAwtImage(), restingBounds)
        assertFalse(entrance.contentEquals(resting), "A new question must visibly announce its arrival")
        mainClock.advanceTimeBy(3000)
        assertTrue(
            resting.contentEquals(accentPixels(captureToImage().toAwtImage(), restingBounds)),
            "The entrance signal must finish instead of pulsing indefinitely",
        )
        runOnIdle { question = question.copy(selected = persistentSetOf("sq")) }
        mainClock.advanceTimeBy(120)
        assertTrue(
            resting.contentEquals(accentPixels(captureToImage().toAwtImage(), restingBounds)),
            "Editing the same question must not repeat its entrance signal",
        )
    }

    private fun verifyQuestionnaireLayout(isDark: Boolean, width: Int) = runSkikoComposeUiTest(
        size = Size(width.toFloat(), 900f),
    ) {
        setContent {
            QuestionnaireTestHost(VisualState, isDark, motion = HbMotion(isReducedMotion = true))
        }
        val colors = HbColors.forHost(isDark = isDark, isDesktop = true).forQuestionnaire()
        val image = captureToImage().toAwtImage()
        VisualState.questions.forEach { question ->
            val card = onNodeWithTag("question-${question.id}").fetchSemanticsNode().boundsInRoot
            assertTrue(card.left >= 0f && card.right <= width, "Question card must fit the viewport")
            assertTrue(card.top >= 0f && card.bottom <= image.height, "Question card must remain fully visible")
            assertEquals(
                colors.surface.toArgb(),
                image.getRGB((card.right - 4f).toInt(), card.center.y.toInt()),
                "Question padding must use the opaque dark oak surface in either host theme",
            )
            onNodeWithText(question.title).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            )
            val actions = when (val kind = question.kind) {
                is QuestionKindUi.Confirm -> listOf("question-yes-${question.id}", "question-no-${question.id}")

                is QuestionKindUi.Choice -> kind.choices.map { "question-choice-${question.id}-${it.id}" } +
                    listOf("question-submit-${question.id}", "question-skip-${question.id}")

                is QuestionKindUi.Text -> listOf("question-text-${question.id}", "question-submit-${question.id}")
            }
            actions.forEach { tag ->
                val node = onNodeWithTag(tag).assertIsDisplayed()
                val bounds = node.getUnclippedBoundsInRoot()
                assertTrue(
                    bounds.left.value >= card.left && bounds.right.value <= card.right &&
                        bounds.top.value >= card.top && bounds.bottom.value <= card.bottom,
                    "Action $tag must remain inside its question card",
                )
            }
        }
        saveQuestionnairePreview("${if (isDark) "dark" else "light"}-$width", image)
    }
}

private val VisualState = QuestionnaireScreenState(
    persistentListOf(
        QuestionUi(
            id = "pick",
            title = "Какую базу данных использовать для проекта?",
            description = "Агент ожидает ваш ответ, чтобы продолжить настройку приложения.",
            kind = QuestionKindUi.Choice(
                persistentListOf(
                    ChoiceUi("pg", "PostgreSQL для рабочего окружения"),
                    ChoiceUi("sq", "SQLite для локального прототипа"),
                ),
                isMultiple = false,
            ),
            isSkippable = true,
            isSubmitting = false,
            selected = persistentSetOf("pg"),
        ),
        QuestionUi(
            id = "go",
            title = "Запустить миграции базы данных?",
            description = "Будут применены подготовленные изменения схемы.",
            kind = QuestionKindUi.Confirm("Разрешить запуск миграций", "Отложить до проверки изменений"),
            isSkippable = false,
            isSubmitting = false,
        ),
        QuestionUi(
            id = "name",
            title = "Как назвать новое рабочее пространство?",
            description = null,
            kind = QuestionKindUi.Text("Название рабочего пространства", isMultiline = false),
            isSkippable = false,
            isSubmitting = false,
            text = "Исследование проекта",
        ),
    ),
)

@Composable
private fun QuestionnaireTestHost(state: QuestionnaireScreenState, isDark: Boolean, motion: HbMotion = HbMotion()) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        HbTheme(darkTheme = isDark, dimensions = HbDimensions.Desktop, motion = motion) {
            Box(Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop)) {
                QuestionnaireScreen(
                    state,
                    {},
                    Modifier.align(Alignment.BottomCenter)
                        .widthIn(max = HbTheme.dimensions.messageMaxWidth)
                        .fillMaxWidth()
                        .padding(HbTheme.spacing.m),
                )
            }
        }
    }
}

private fun accentPixels(image: BufferedImage, bounds: Rect): IntArray {
    val left = (bounds.left + 12f).toInt()
    val right = (bounds.right - 12f).toInt()
    val top = (bounds.top + 1f).toInt()
    val height = (bounds.height.toInt() - 2).coerceAtLeast(1)
    return image.getRGB(left, top, right - left, height, null, 0, right - left)
}

private fun saveQuestionnairePreview(name: String, image: BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create questionnaire preview directory" }
    check(ImageIO.write(image, "png", File(directory, "questionnaire-$name.png"))) { "PNG encoder is unavailable" }
}
