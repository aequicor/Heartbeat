package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class HbNativeContentViewportUiTest {
    @Test
    fun `desktop dialog escapes its source pane and can establish its own native viewport`() =
        runSkikoComposeUiTest(size = Size(800f, 600f)) {
            var parentLeft by mutableStateOf(500.dp)
            var source: Rect? = null
            var inherited: Rect? = Rect.Zero
            var own: Rect? = null
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme {
                        Box(Modifier.fillMaxSize()) {
                            HbNativeContentViewport(Modifier.offset(parentLeft, 100.dp).size(100.dp, 300.dp)) {
                                val sourceViewport = LocalHbNativeContentViewport.current
                                SideEffect { source = sourceViewport }
                                Box(Modifier.fillMaxSize())
                                HbPlatformDialog(onDismissRequest = {}) {
                                    val inheritedViewport = LocalHbNativeContentViewport.current
                                    SideEffect { inherited = inheritedViewport }
                                    HbNativeContentViewport(
                                        modifier = Modifier.size(400.dp, 300.dp),
                                        overlapInsets = PaddingValues(top = 20.dp, bottom = 40.dp),
                                    ) {
                                        val ownViewport = LocalHbNativeContentViewport.current
                                        SideEffect { own = ownViewport }
                                        Box(Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
            }
            runOnIdle {
                assertEquals(Rect(500f, 100f, 600f, 400f), source)
                assertNull(inherited)
                assertEquals(Size(400f, 240f), own?.size)
            }
            val original = own
            runOnIdle { parentLeft = 50.dp }
            runOnIdle {
                assertEquals(Rect(50f, 100f, 150f, 400f), source)
                assertNull(inherited)
                assertEquals(original, own)
            }
        }

    @Test
    fun `pinned heading and overlay use the larger top obstruction`() = runSkikoComposeUiTest(
        size = Size(300f, 200f),
    ) {
        var pinnedBottom by mutableIntStateOf(60)
        var observed: Rect? = null
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbNativeContentViewport(
                    modifier = Modifier.fillMaxSize(),
                    overlapInsets = PaddingValues(top = 20.dp, bottom = 40.dp),
                    pinnedHeaderBottom = pinnedBottom,
                ) {
                    val viewport = LocalHbNativeContentViewport.current
                    SideEffect { observed = viewport }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        runOnIdle { assertEquals(Rect(0f, 60f, 300f, 160f), observed) }
        runOnIdle { pinnedBottom = 10 }
        runOnIdle { assertEquals(Rect(0f, 20f, 300f, 160f), observed) }
    }

    @Test
    fun `sticky host shares a nested native viewport following move resize and overlay changes`() =
        runSkikoComposeUiTest(size = Size(600f, 500f)) {
            var parent by mutableStateOf(Rect(60f, 0f, 300f, 400f))
            var top by mutableStateOf(30.dp)
            var height by mutableStateOf(240.dp)
            var footer by mutableStateOf(40.dp)
            var observed: Rect? = null
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalHbNativeContentViewport provides parent,
                ) {
                    HbTheme {
                        Box(Modifier.fillMaxSize()) {
                            HbStickyHeaderHost(
                                state = rememberLazyListState(),
                                stickyHeaderKeyPrefix = "header:",
                                header = { _, _ -> },
                                modifier = Modifier.offset(40.dp, top).size(320.dp, height),
                                overlapInsets = PaddingValues(top = 20.dp, bottom = footer),
                            ) {
                                val viewport = LocalHbNativeContentViewport.current
                                SideEffect { observed = viewport }
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            runOnIdle { assertEquals(Rect(60f, 50f, 300f, 230f), observed) }
            runOnIdle {
                top = 50.dp
                height = 300.dp
                footer = 80.dp
            }
            runOnIdle { assertEquals(Rect(60f, 70f, 300f, 270f), observed) }
            runOnIdle { parent = Rect(500f, 0f, 600f, 500f) }
            runOnIdle { assertEquals(Rect.Zero, observed) }
        }
}
