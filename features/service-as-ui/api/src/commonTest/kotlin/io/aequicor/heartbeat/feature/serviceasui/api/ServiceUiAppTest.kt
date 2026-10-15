package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.time.Instant

class ServiceUiAppTest {
    private val owner = UiOwner("harness", "gym")
    private val host = UiHostId("studio.pane-0")
    private val context = UiHostContext(UiCallContext(preselected = mapOf("harness" to setOf("gym"))))
    private val surface = UiSurface(
        UiSurfaceContent(owner, UiSurfaceName("gym"), UiPlacement.Card, "Gym", spec = "{}"),
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
    )
    private val app = UiApp(surface.id, context)
    private val ready = ServiceUiState.Ready(
        hosts = mapOf(host to context),
        library = UiLibrary(listOf(surface)),
        apps = mapOf(host to app),
    )

    @Test
    fun `preselected app opens without a chat and keeps its identity when closed`() {
        val expanded = ready.copy(apps = mapOf(host to app.copy(isExpanded = true, sequence = 1)), appSequence = 1)
        ServiceUiMachineSpec.assertTransition(
            ready,
            ServiceUiIntent.Public.OpenApp(host, surface.id, context),
            expanded,
        )
        ServiceUiMachineSpec.assertTransition(
            expanded,
            ServiceUiIntent.Public.CloseApp(host, 1),
            expanded.copy(apps = mapOf(host to app.copy(sequence = 1))),
        )
        ServiceUiMachineSpec.assertIgnored(expanded, ServiceUiIntent.Public.CloseApp(host, 0))
    }

    @Test
    fun `stale context wrong selection and disabled app cannot open`() {
        val open = ServiceUiIntent.Public.OpenApp(host, surface.id, context)
        ServiceUiMachineSpec.assertIgnored(ready, open.copy(expectedContext = UiHostContext()))
        ServiceUiMachineSpec.assertIgnored(ready.copy(apps = emptyMap()), open)
        ServiceUiMachineSpec.assertIgnored(
            ready.copy(library = UiLibrary(listOf(surface.copy(isEnabled = false)))),
            open,
        )
        ServiceUiMachineSpec.assertIgnored(ServiceUiState.Disabled(), open)
    }

    @Test
    fun `host rebind and unbind revoke selected app including expanded presentation`() {
        ServiceUiMachineSpec.assertTransition(
            ready,
            ServiceUiIntent.Public.BindHost(host, UiHostContext()),
            ready.copy(hosts = mapOf(host to UiHostContext()), apps = emptyMap()),
        )
        ServiceUiMachineSpec.assertTransition(
            ready,
            ServiceUiIntent.Public.UnbindHost(host),
            ready.copy(hosts = emptyMap(), apps = emptyMap()),
        )
    }

    @Test
    fun `resolution is bound to the exact host and library snapshot`() {
        val request = ServiceUiIntent.Internal.ResolveApp(host, UiAppTarget(surface.id), context, 0)
        ServiceUiMachineSpec.assertTransition(ready.copy(apps = emptyMap()), request, ready)
        ServiceUiMachineSpec.assertIgnored(ready, request.copy(expectedLibraryRevision = 1))
        ServiceUiMachineSpec.assertIgnored(ready, request.copy(expectedContext = UiHostContext()))
        ServiceUiMachineSpec.assertTransition(ready, request.copy(target = null), ready.copy(apps = emptyMap()))
    }
}
