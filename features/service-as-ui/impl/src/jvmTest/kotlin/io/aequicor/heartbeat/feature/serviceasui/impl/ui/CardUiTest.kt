package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardPhase
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardState
import kotlinx.collections.immutable.persistentMapOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CardUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `evicted and unavailable cards explain themselves`() = runSkikoComposeUiTest(size = Size(480f, 400f)) {
        setContent { HbTheme(darkTheme = false) { CardView(CardState(CardPhase.Missing), persistentMapOf()) } }
        onNodeWithText("This card is no longer stored").assertIsDisplayed()
    }

    @Test
    fun `an unavailable card keeps its title`() = runSkikoComposeUiTest(size = Size(480f, 400f)) {
        setContent {
            HbTheme(darkTheme = true) { CardView(CardState(CardPhase.Unavailable, "PR map"), persistentMapOf()) }
        }
        onNodeWithText("PR map").assertIsDisplayed()
        onNodeWithText("The interface of this card is not available").assertIsDisplayed()
    }
}
