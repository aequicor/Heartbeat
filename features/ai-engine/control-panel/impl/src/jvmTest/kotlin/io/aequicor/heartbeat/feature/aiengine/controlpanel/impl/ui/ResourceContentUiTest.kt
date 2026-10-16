package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigPageUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceFileUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceInventoryUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceKindUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceOriginUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceStateUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ResourceContentUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useRussian() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `resource statuses fit compact and desktop layouts in both themes`() {
        for (width in listOf(420, 1280)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 900f)) {
                    var refreshes = 0
                    val files = ResourceStateUi.entries.map {
                        ResourceFileUi(
                            ResourceKindUi.Skill,
                            ResourceOriginUi.Project,
                            ".agents/skills/a-long-resource-name/${it.name}/SKILL.md",
                            "Проверка проекта ${it.name}",
                            it,
                            "Описание навыка с длинным текстом для проверки переноса строк в узком окне.",
                        )
                    }.toImmutableList()
                    val page = ConfigPageUi(
                        "resources",
                        TextUi("Расширения и навыки", persistentMapOf()),
                        persistentListOf(),
                        true,
                    )
                    val state = EngineControlPanelState(
                        "pi",
                        config = ConfigFormUi(persistentListOf(page)),
                        openPage = page.id,
                        resources = ResourceInventoryUi(files, 2, true),
                        isLoading = false,
                    )
                    setContent {
                        HbTheme(darkTheme = dark) {
                            EngineControlPanelContent(state, {
                                if (it == EngineControlPanelIntent.RefreshResources) refreshes++
                            })
                        }
                    }
                    onRoot().performKeyInput {
                        pressKey(Key.Tab)
                        pressKey(Key.Tab)
                    }
                    onNodeWithTag("resources-refresh").assertIsFocused().performClick()
                    assertEquals(1, refreshes)
                    val directory = File("build/reports/resource-inventory").apply { mkdirs() }
                    check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$width-$dark.png")))
                    files.forEach {
                        onNodeWithTag("resource:${it.origin}:${it.path}").performScrollTo().assertIsDisplayed()
                    }
                }
            }
        }
    }
}
