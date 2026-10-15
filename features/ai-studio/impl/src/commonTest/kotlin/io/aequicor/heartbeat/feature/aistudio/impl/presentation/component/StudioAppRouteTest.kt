package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioAppKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioComposerSlotKey
import io.aequicor.heartbeat.feature.aistudio.screenmodel.studioStartSlotKey
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiAppRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSlotRoute
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioAppRouteTest {
    @Test
    fun `one app route stays separate from both legacy slots in the same pane`() {
        assertEquals(ServiceUiAppRoute("host"), studioSurfaceRoute(studioAppKey(0), "host"))
        assertEquals(
            ServiceUiSlotRoute("host", UiSlot.Composer),
            studioSurfaceRoute(studioComposerSlotKey(0), "host"),
        )
        assertEquals(ServiceUiSlotRoute("host", UiSlot.Start), studioSurfaceRoute(studioStartSlotKey(0), "host"))
    }
}
