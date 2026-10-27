package io.aequicor.heartbeat.feature.appupdate.impl.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.AppBuildUi
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.AppUpdatesScreenIntent
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.AppUpdatesScreenState
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.BundleUi
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.UpdateStageUi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppUpdatesUiTest {
    private val build = AppBuildUi("1.4.0", "t1", false, listOf(BundleUi("studio", "1.4.2")))

    @Test
    fun `the build and its bundles are shown and a check emits an intent`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            val intents = mutableListOf<AppUpdatesScreenIntent>()
            setContent {
                HbTheme {
                    AppUpdatesContent(
                        AppUpdatesScreenState(build, UpdateStageUi.UpToDate),
                        intents::add,
                        {},
                        null,
                    )
                }
            }
            onNodeWithTag("updates-version").assertIsDisplayed()
            onNodeWithTag("updates-train").assertIsDisplayed()
            onNodeWithTag("updates-bundle-studio").assertIsDisplayed()
            onNodeWithTag("updates-check").performClick()
            assertEquals(listOf<AppUpdatesScreenIntent>(AppUpdatesScreenIntent.Check), intents)
        }

    @Test
    fun `an available release can be installed and a running check cannot be repeated`() =
        runSkikoComposeUiTest(size = Size(900f, 900f)) {
            val state = mutableStateOf(AppUpdatesScreenState(build, UpdateStageUi.Available, version = "1.5.0"))
            val intents = mutableListOf<AppUpdatesScreenIntent>()
            setContent { HbTheme { AppUpdatesContent(state.value, intents::add, {}, null) } }
            onNodeWithTag("updates-install").performClick()
            assertEquals(listOf<AppUpdatesScreenIntent>(AppUpdatesScreenIntent.Install), intents)
            runOnIdle { state.value = AppUpdatesScreenState(build, UpdateStageUi.Checking) }
            onNodeWithTag("updates-check").assertIsNotEnabled()
        }

    @Test
    fun `a manual install opens the release page`() = runSkikoComposeUiTest(size = Size(900f, 900f)) {
        val opened = mutableListOf<String>()
        setContent {
            HbTheme {
                val state = AppUpdatesScreenState(
                    build = build,
                    stage = UpdateStageUi.ManualInstall,
                    version = "1.5.0",
                    pageUrl = "https://example.test/p",
                )
                AppUpdatesContent(state, {}, opened::add, null)
            }
        }
        onNodeWithTag("updates-open-page").performClick()
        assertEquals(listOf("https://example.test/p"), opened)
    }
}
