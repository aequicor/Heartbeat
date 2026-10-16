package io.aequicor.heartbeat.feature.applogs.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsActionUi
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsNoticeUi
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsScreenIntent
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsScreenState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppLogsUiTest {
    @Test
    fun `all four rows emit operations and busy disables them`() = runSkikoComposeUiTest(size = Size(900f, 800f)) {
        val state = mutableStateOf(AppLogsScreenState())
        val intents = mutableListOf<AppLogsScreenIntent>()
        setContent { HbTheme { AppLogsContent(state.value, intents::add, null) } }
        AppLogsActionUi.entries.forEach { action ->
            onNodeWithTag("logs-action-$action").assertIsDisplayed().performClick()
            assertEquals(AppLogsScreenIntent.Execute(action), intents.last())
        }
        runOnIdle { state.value = AppLogsScreenState(isBusy = true) }
        AppLogsActionUi.entries.forEach { action -> onNodeWithTag("logs-action-$action").assertIsNotEnabled() }
    }

    @Test
    fun `keyboard can reach every action and error provides retry`() = runSkikoComposeUiTest(size = Size(420f, 800f)) {
        val state = mutableStateOf(AppLogsScreenState())
        val intents = mutableListOf<AppLogsScreenIntent>()
        setContent { HbTheme { AppLogsContent(state.value, intents::add, null) } }
        onNodeWithTag("app-logs").performKeyInput { pressKey(Key.Tab) }
        AppLogsActionUi.entries.forEachIndexed { index, action ->
            val node = onNodeWithTag("logs-action-$action").assertIsFocused()
            if (index < AppLogsActionUi.entries.lastIndex) node.performKeyInput { pressKey(Key.Tab) }
        }
        runOnIdle { state.value = AppLogsScreenState(notice = AppLogsNoticeUi.Failure) }
        onNodeWithTag("logs-retry").performClick()
        assertEquals(AppLogsScreenIntent.Retry, intents.last())
    }

    @Test
    fun `renders desktop and compact snapshots in both themes`() {
        listOf(1280, 420).forEach { width ->
            listOf(false, true).forEach { isDark ->
                runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = isDark) {
                                AppLogsContent(AppLogsScreenState(notice = AppLogsNoticeUi.Saved), {}, null)
                            }
                        }
                    }
                    AppLogsActionUi.entries.forEach { onNodeWithTag("logs-action-$it").assertIsDisplayed() }
                    val file = File("build/reports/snapshots/logs-$width-${if (isDark) "dark" else "light"}.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(captureToImage().toAwtImage(), "png", file)
                }
            }
        }
    }
}
