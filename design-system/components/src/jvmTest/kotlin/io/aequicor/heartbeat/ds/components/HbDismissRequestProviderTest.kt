package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class HbDismissRequestProviderTest {
    @Test
    fun `a non-modal surface forwards dismissal without declaring a modal host`() = runSkikoComposeUiTest {
        var calls = 0
        var surfaceRequest: (() -> Unit)? = null
        var modalRequest: (() -> Unit)? = null
        setContent {
            HbDismissRequestProvider({ calls++ }) {
                val surface = LocalHbSurfaceDismissRequest.current
                val modal = LocalHbDialogDismissRequest.current
                SideEffect {
                    surfaceRequest = surface
                    modalRequest = modal
                }
            }
        }
        runOnIdle {
            assertNull(modalRequest, "A native host must keep ordinary Tab traversal for a floating surface")
            assertNotNull(surfaceRequest).invoke()
            assertEquals(1, calls)
        }
    }

    @Test
    fun `surface and modal dismissal scopes retain their own callbacks when nested`() = runSkikoComposeUiTest {
        val surfaceRequest = {}
        val modalRequest = {}
        var surfaceOutsideModal: (() -> Unit)? = null
        var modalInsideSurface: (() -> Unit)? = null
        var surfaceInsideModal: (() -> Unit)? = null
        var modalOutsideSurface: (() -> Unit)? = null
        setContent {
            HbDismissRequestProvider(surfaceRequest) {
                CompositionLocalProvider(LocalHbDialogDismissRequest provides modalRequest) {
                    val surface = LocalHbSurfaceDismissRequest.current
                    val modal = LocalHbDialogDismissRequest.current
                    SideEffect {
                        surfaceOutsideModal = surface
                        modalInsideSurface = modal
                    }
                }
            }
            CompositionLocalProvider(LocalHbDialogDismissRequest provides modalRequest) {
                HbDismissRequestProvider(surfaceRequest) {
                    val surface = LocalHbSurfaceDismissRequest.current
                    val modal = LocalHbDialogDismissRequest.current
                    SideEffect {
                        surfaceInsideModal = surface
                        modalOutsideSurface = modal
                    }
                }
            }
        }
        runOnIdle {
            assertSame(surfaceRequest, surfaceOutsideModal)
            assertSame(surfaceRequest, surfaceInsideModal)
            assertSame(modalRequest, modalInsideSurface)
            assertSame(modalRequest, modalOutsideSurface)
        }
    }
}
