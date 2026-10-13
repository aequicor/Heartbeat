package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlin.test.Test
import kotlin.time.Instant

class ServiceUiCardFocusTest {
    private val owner = UiOwner("harness", "fitness")
    private val host = UiHostId("studio.pane-0")
    private val context = UiHostContext(
        UiCallContext(session = SessionRef(EngineId("engine"), SessionSourceId("source"), "chat")),
        UiDockId("studio.side"),
    )
    private val surface = UiSurface(
        UiSurfaceContent(owner, UiSurfaceName("workout"), UiPlacement.Composer, "Workout", spec = "{}"),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
    private val ready = ServiceUiState.Ready(
        hosts = mapOf(host to context),
        library = UiLibrary(listOf(surface)),
    )
    private val request = ServiceUiIntent.Internal.RequestFocusCard(host, surface.id, owner, "card", context)

    @Test
    fun `focus carries the original context and never opens a dock`() {
        ServiceUiMachineSpec.assertTransition(
            ready,
            request,
            ready,
            outputs = listOf(ServiceUiOutput.CardFocusRequested(host, "card", context)),
        )
        ServiceUiMachineSpec.assertIgnored(ready, ServiceUiIntent.Public.OpenPanel(host, surface.id))
    }

    @Test
    fun `stale context wrong owner disabled surface and missing chat cannot focus`() {
        ServiceUiMachineSpec.assertIgnored(ready.copy(hosts = mapOf(host to UiHostContext())), request)
        ServiceUiMachineSpec.assertIgnored(ready, request.copy(owner = UiOwner("harness", "other")))
        ServiceUiMachineSpec.assertIgnored(ready.copy(library = UiLibrary()), request)
        ServiceUiMachineSpec.assertIgnored(
            ready.copy(library = UiLibrary(listOf(surface.copy(isEnabled = false)))),
            request,
        )
        ServiceUiMachineSpec.assertIgnored(
            ready.copy(hosts = mapOf(host to UiHostContext())),
            request.copy(expectedContext = UiHostContext()),
        )
        ServiceUiMachineSpec.assertIgnored(ServiceUiState.Disabled(), request)
    }
}
