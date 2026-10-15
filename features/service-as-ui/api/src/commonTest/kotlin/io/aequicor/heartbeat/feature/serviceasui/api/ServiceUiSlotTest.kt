package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent.Internal
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent.Public
import kotlin.test.Test
import kotlin.time.Instant

class ServiceUiSlotTest {
    private val host = UiHostId("chat")
    private val context = UiHostContext(UiCallContext(preselected = mapOf("harness" to setOf("active"))))
    private val ready = ServiceUiState.Ready(hosts = mapOf(host to context), library = UiLibrary(revision = 4))
    private val present = ready.copy(slots = mapOf(host to setOf(UiSlot.Context)))
    private val resolve = Internal.ResolveSlot(host, UiSlot.Context, true, context, expectedLibraryRevision = 4)
    private val spec = ServiceUiMachineSpec

    @Test
    fun `slot presence follows resolution of the exact bound context and library`() {
        spec.assertTransition(ready, resolve, present)
        spec.assertTransition(present, resolve, present)
        val another = present.copy(slots = mapOf(host to setOf(UiSlot.Context, UiSlot.Start)))
        spec.assertTransition(present, resolve.copy(slot = UiSlot.Start), another)
        spec.assertTransition(
            another,
            resolve.copy(isPresent = false),
            ready.copy(slots = mapOf(host to setOf(UiSlot.Start))),
        )
        spec.assertTransition(present, resolve.copy(isPresent = false), ready)
    }

    @Test
    fun `stale or unavailable slot resolutions cannot reserve host space`() {
        spec.assertIgnored(ready, resolve.copy(expectedLibraryRevision = 3))
        spec.assertIgnored(ready, resolve.copy(expectedLibraryRevision = 5))
        spec.assertIgnored(ready, resolve.copy(expectedContext = UiHostContext()))
        spec.assertIgnored(ready.copy(hosts = emptyMap()), resolve)
        spec.assertIgnored(ServiceUiState.Disabled(hosts = ready.hosts), resolve)
        spec.assertIgnored(ServiceUiState.Loading(hosts = ready.hosts), resolve)
        spec.assertIgnored(ServiceUiState.Failed(hosts = ready.hosts, attempt = 1), resolve)
    }

    @Test
    fun `unchanged binding preserves presence but rebinding unbinding and disabling revoke it`() {
        spec.assertTransition(present, Public.BindHost(host, context), present)
        val changed = UiHostContext()
        spec.assertTransition(present, Public.BindHost(host, changed), ready.copy(hosts = mapOf(host to changed)))
        spec.assertTransition(present, Public.UnbindHost(host), ready.copy(hosts = emptyMap()))
        spec.assertTransition(
            present,
            Internal.AvailabilityChanged(false),
            ServiceUiState.Disabled(hosts = ready.hosts),
        )
    }

    @Test
    fun `committing a library revision clears previous slot presence until it resolves again`() {
        val at = Instant.fromEpochSeconds(1)
        val content = UiSurfaceContent(
            UiOwner("harness", "active"),
            UiSurfaceName("summary"),
            UiPlacement.Context,
            "Summary",
            spec = "{}",
        )
        val surface = UiSurface(content, createdAt = at, updatedAt = at)
        val next = UiLibrary(listOf(surface), revision = 5)
        val write = UiWrite("put", UiWriteOrigin.User, UiWriteOp.Put(content, 0, null, at))
        val pending = present.copy(write = UiPendingWrite(write, UiWriteResult.Applied(next, surface)))
        val committed = ready.copy(library = next)
        spec.assertTransition(
            pending,
            Internal.Persisted(write.id),
            committed,
            outputs = listOf(ServiceUiOutput.EditApplied(write.id, surface)),
        )
        spec.assertIgnored(committed, resolve)
        spec.assertTransition(
            committed,
            resolve.copy(expectedLibraryRevision = 5),
            committed.copy(slots = mapOf(host to setOf(UiSlot.Context))),
        )
    }
}
