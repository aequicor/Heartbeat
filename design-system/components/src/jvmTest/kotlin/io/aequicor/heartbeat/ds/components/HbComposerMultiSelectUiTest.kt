package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbComposerMultiSelectUiTest {
    @Test
    fun `selection remains open and search does not discard hidden selections`() = runSkikoComposeUiTest {
        var selected by mutableStateOf(emptySet<String>())
        var expanded by mutableStateOf(false)
        setContent {
            HbTheme {
                HbComposerMultiSelect(
                    "Choices",
                    options(selected),
                    labels(selected.size),
                    expanded,
                    { expanded = it },
                    { selected = if (it in selected) selected - it else selected + it },
                    { selected = emptySet() },
                )
            }
        }
        onNodeWithContentDescription("Harnesses").performClick()
        onNodeWithText("Разработка").performClick().assertIsOn()
        onNodeWithText("Ревью кода").performClick().assertIsOn()
        assertTrue(expanded)
        onNodeWithText("Найти харнесс").performTextInput("Описание")
        onNodeWithText("Разработка").assertDoesNotExist()
        onNodeWithText("Выбрано: 2").assertExists()
        onNodeWithText("Очистить").performClick()
        assertEquals(emptySet(), selected)
        onNodeWithText("Документация").assertIsOff()
        onNodeWithTag("multi-select-popup").performKeyInput { pressKey(Key.Escape) }
        assertEquals(false, expanded)
    }

    @Test
    fun `desktop tablet and phone keep search selection and dismissal within the window in both themes`() {
        for (size in listOf(Size(1280f, 800f), Size(1024f, 768f), Size(420f, 900f))) {
            for (dark in listOf(false, true)) assertResponsivePicker(size, dark)
        }
    }

    private fun assertResponsivePicker(size: Size, dark: Boolean) = runSkikoComposeUiTest(size = size) {
        var expanded by mutableStateOf(true)
        val isSheet = size.width < 720f
        setContent {
            HbTheme(
                darkTheme = dark,
                dimensions = if (size.width == 1280f) HbDimensions.Desktop else HbDimensions.Mobile,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    HbComposerMultiSelect(
                        "Харнессы · 2",
                        options(setOf("dev", "review")),
                        labels(2),
                        expanded,
                        { expanded = it },
                        {},
                        {},
                        icon = HbIcons.Layers,
                    )
                }
            }
        }
        val surface = onNodeWithTag(if (isSheet) "multi-select-sheet" else "multi-select-popup")
        val bounds = surface.fetchSemanticsNode().boundsInWindow
        assertTrue(bounds.left >= 0 && bounds.right <= size.width)
        assertTrue(bounds.top >= 0 && bounds.bottom <= size.height)
        onNodeWithText("Выбрано: 2").assertExists()
        val directory = File("build/reports/harness-picker").apply { mkdirs() }
        val output = File(directory, "${size.width}-$dark.png")
        check(ImageIO.write(captureToImage().toAwtImage(), "png", output))
        if (isSheet) {
            onNodeWithText("Готово").performClick()
            assertEquals(false, expanded)
        }
    }

    private fun options(selected: Set<String>) = listOf(
        HbComposerAction("dev", "Разработка", "Код и тесты", isSelected = "dev" in selected),
        HbComposerAction("review", "Ревью кода", "Проверка изменений", isSelected = "review" in selected),
        HbComposerAction("docs", "Документация", "Описание решений", isSelected = "docs" in selected),
    ).toImmutableList()

    private fun labels(count: Int) = HbMultiSelectLabels(
        "Harnesses",
        "Найти харнесс",
        "Очистить поиск",
        "Ничего не найдено",
        "Выбрано: $count",
        "Очистить",
        "Готово",
    )
}
