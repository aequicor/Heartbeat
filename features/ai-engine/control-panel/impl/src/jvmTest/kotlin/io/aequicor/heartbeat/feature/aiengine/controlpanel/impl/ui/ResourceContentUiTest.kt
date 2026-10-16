package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

    @Test
    fun `each inventory state shows one matching message and long lists open a page at a time`() =
        runSkikoComposeUiTest(size = Size(420f, 900f)) {
            var shown by mutableStateOf<Pair<ResourceInventoryUi?, Boolean>>(null to true)
            setContent {
                HbTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ResourceContent(shown.first, shown.second, isRefreshEnabled = true, onRefresh = {})
                    }
                }
            }
            onNodeWithText("Чтение файлов ресурсов…").assertIsDisplayed()
            shown = null to false
            onNodeWithText("Загрузите список, чтобы увидеть источник и состояние ресурсов.").assertIsDisplayed()
            shown = ResourceInventoryUi(persistentListOf(), 0, false) to false
            onNodeWithText("В этих папках файлы ресурсов не найдены.").assertIsDisplayed()
            shown = ResourceInventoryUi(persistentListOf(), 1, true) to false
            onAllNodesWithTag("resource-inventory").assertCountEquals(1)
            onNodeWithText("Вне инвентаря: 1 внешняя запись.").assertIsDisplayed()
            onAllNodes(hasText("В этих папках файлы ресурсов не найдены.")).assertCountEquals(0)
            val many = (0 until 250).map {
                ResourceFileUi(
                    ResourceKindUi.Prompt,
                    ResourceOriginUi.Profile,
                    "prompts/p$it.md",
                    "p$it",
                    ResourceStateUi.Enabled,
                    null,
                )
            }.toImmutableList()
            shown = ResourceInventoryUi(many, 0, false) to false
            onAllNodes(hasTestTagPrefix("resource:")).assertCountEquals(100)
            onNodeWithTag("resources-more").performScrollTo().performClick()
            onAllNodes(hasTestTagPrefix("resource:")).assertCountEquals(200)
            onNodeWithTag("resources-more").performScrollTo().performClick()
            onAllNodes(hasTestTagPrefix("resource:")).assertCountEquals(250)
            onAllNodesWithTag("resources-more").assertCountEquals(0)
        }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("test tag starting with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
