package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPresentation
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentSizing
import io.aequicor.heartbeat.feature.browser.impl.domain.OpenBrowserDocument
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DocumentSizeTest {
    @Test
    fun `host content grows and shrinks within current constraints rather than inline height presets`() =
        runSkikoComposeUiTest(size = Size(800f, 1200f)) {
            val registration = OpenBrowserDocument(
                "retained",
                BrowserDocument("<main/>", "Page"),
                MutableStateFlow(null),
            )
            val surface = BrowserDocumentSurface(registration, presentation = BrowserDocumentPresentation.Content)
            var intrinsic by mutableIntStateOf(100)
            var limit by mutableIntStateOf(1100)
            var isTight by mutableStateOf(false)
            var measured = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.fillMaxSize()) {
                        DocumentViewport(
                            Modifier.fillMaxWidth().heightIn(max = limit.dp)
                                .then(if (isTight) Modifier.fillMaxSize() else Modifier)
                                .documentSize(surface, intrinsic.dp, HbDimensions.Desktop),
                        ) {
                            Box(Modifier.onSizeChanged { measured = it.height })
                        }
                    }
                }
            }
            runOnIdle { assertEquals(100, measured) }
            runOnIdle { intrinsic = 300 }
            runOnIdle { assertEquals(300, measured) }
            runOnIdle { intrinsic = 2000 }
            runOnIdle { assertEquals(1100, measured) }
            runOnIdle { limit = 700 }
            runOnIdle { assertEquals(700, measured) }
            runOnIdle { intrinsic = 80 }
            runOnIdle { assertEquals(80, measured) }
            runOnIdle { isTight = true }
            runOnIdle { assertEquals(700, measured) }
            runOnIdle { isTight = false }
            runOnIdle { assertEquals(80, measured) }
        }

    @Test
    fun `registration content presentation keeps the existing inline maximum`() =
        runSkikoComposeUiTest(size = Size(800f, 1200f)) {
            val registration = OpenBrowserDocument(
                "inline",
                BrowserDocument("<main/>", "Page", sizing = BrowserDocumentSizing.Content),
                MutableStateFlow(null),
            )
            val surface = BrowserDocumentSurface(registration)
            var measured = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.fillMaxSize()) {
                        DocumentViewport(Modifier.documentSize(surface, 2000.dp, HbDimensions.Desktop)) {
                            Box(Modifier.onSizeChanged { measured = it.height })
                        }
                    }
                }
            }
            runOnIdle { assertEquals(HbDimensions.Desktop.webDocumentMaxHeight.value.toInt(), measured) }
        }
}
