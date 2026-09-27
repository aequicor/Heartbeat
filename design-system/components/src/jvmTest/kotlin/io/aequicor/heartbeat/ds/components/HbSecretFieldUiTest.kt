package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbSecretFieldUiTest {
    @Test
    fun `secret field reports the typed value but shows only bullets`() = runSkikoComposeUiTest {
        var value by mutableStateOf("")
        setContent {
            HbTheme {
                HbTextField(value, { value = it }, Modifier.testTag("secret"), isSecret = true)
            }
        }
        val field = onNodeWithTag("secret")
        field.performTextInput("sk-123")
        runOnIdle { assertEquals("sk-123", value) }
        val config = field.fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in config)
        assertEquals("••••••", config[SemanticsProperties.EditableText].text)
    }
}
