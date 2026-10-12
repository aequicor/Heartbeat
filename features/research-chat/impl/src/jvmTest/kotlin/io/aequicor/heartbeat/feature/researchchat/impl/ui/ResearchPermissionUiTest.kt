package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionKind
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionOptionUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPermissionUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ResearchPermissionUiTest {
    @Test
    fun `single choice is a radio group whose selection is not toggled off`() =
        runSkikoComposeUiTest(size = Size(600f, 600f)) {
            val intents = mutableListOf<ResearchScreenIntent>()
            val permission = ResearchPermissionUi(
                "request",
                "Pick a source",
                null,
                persistentListOf(
                    ResearchPermissionOptionUi("submit", "Continue", isSkip = false),
                    ResearchPermissionOptionUi("skip", "Skip", isSkip = true),
                ),
                kind = ResearchPermissionKind.SingleChoice,
                choices = persistentListOf("first" to "First", "second" to "Second"),
                min = 1,
                max = 1,
            )
            setContent { HbTheme(darkTheme = false) { ResearchPermission(permission, { intents += it }) } }

            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(2)
            onNodeWithText("Second").performClick()
            onNodeWithText("Second").performClick()
            onNodeWithText("Second").assertIsSelected()
            onNodeWithText("Continue").performClick()

            val answer = PermissionAnswer.Selected(listOf("second"))
            assertEquals(
                listOf<ResearchScreenIntent>(ResearchScreenIntent.DecidePermission("request", "submit", answer)),
                intents,
            )
        }
}
