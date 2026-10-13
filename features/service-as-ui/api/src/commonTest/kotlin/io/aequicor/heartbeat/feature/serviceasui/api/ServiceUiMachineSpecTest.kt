package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent.Internal
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent.Public
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class ServiceUiMachineSpecTest {
    @Test
    fun `enabling a disabled machine loads the library and keeps bindings`() {
        val disabled = ServiceUiState.Disabled(Hosts, mapOf("harness" to Complete))

        Spec.assertTransition(
            from = disabled,
            intent = Internal.AvailabilityChanged(true),
            to = ServiceUiState.Loading(Hosts, mapOf("harness" to Complete)),
            effects = listOf(ServiceUiEffect.Load),
        )
        Spec.assertIgnored(disabled, Internal.AvailabilityChanged(false))
        Spec.assertIgnored(Ready, Internal.AvailabilityChanged(true))
    }

    @Test
    fun `hosts and owners are remembered in every state`() {
        val context = UiHostContext(UiCallContext(), Dock)
        for (state in listOf(ServiceUiState.Disabled(), ServiceUiState.Loading(), ServiceUiState.Failed(attempt = 1))) {
            val bound = Spec.assertTransition(state, Public.BindHost(Host, context), state.bound(Host, context)).to
            Spec.assertTransition(bound, Public.UnbindHost(Host), state)
            Spec.assertTransition(state, Internal.OwnersChanged("harness", Complete), state.owned("harness", Complete))
            Spec.assertIgnored(state, Public.UnbindHost(Host))
        }
        val full = ServiceUiState.Disabled((0 until ServiceUiLimits.HOSTS).associate { UiHostId("p$it") to context })
        Spec.assertIgnored(full, Public.BindHost(Host, context))
        Spec.assertTransition(full, Public.BindHost(UiHostId("p0"), UiHostContext()), full.bound(UiHostId("p0")))
    }

    @Test
    fun `edits before the library is ready are unavailable`() {
        val write = write("w1", put("map"))
        for (state in listOf(ServiceUiState.Disabled(), ServiceUiState.Loading(), ServiceUiState.Failed(attempt = 3))) {
            Spec.assertTransition(
                from = state,
                intent = Internal.Edit(write),
                to = state,
                outputs = listOf(ServiceUiOutput.EditRejected("w1", UiEditRejection.Unavailable)),
            )
        }
    }

    @Test
    fun `loading becomes ready and failures retry a limited number of times`() {
        Spec.assertTransition(
            from = ServiceUiState.Loading(Hosts),
            intent = Internal.Loaded(Library),
            to = ServiceUiState.Ready(Hosts, library = Library),
        )
        Spec.assertTransition(
            from = ServiceUiState.Loading(attempt = 0),
            intent = Internal.LoadFailed,
            to = ServiceUiState.Failed(attempt = 1),
            effects = listOf(ServiceUiEffect.RetryLoad(1)),
        )
        Spec.assertTransition(
            from = ServiceUiState.Loading(attempt = SERVICE_UI_LOAD_ATTEMPTS - 1),
            intent = Internal.LoadFailed,
            to = ServiceUiState.Failed(attempt = SERVICE_UI_LOAD_ATTEMPTS),
        )
        for (intent in listOf(Internal.RetryLoad, Public.Reload)) {
            Spec.assertTransition(
                from = ServiceUiState.Failed(Hosts, attempt = 2),
                intent = intent,
                to = ServiceUiState.Loading(Hosts, attempt = 2),
                effects = listOf(ServiceUiEffect.Load),
            )
        }
        Spec.assertIgnored(Ready, Public.Reload)
        Spec.assertIgnored(Ready, Internal.Loaded(UiLibrary()))
    }

    @Test
    fun `loading purges owners a complete snapshot no longer lists`() {
        val gone = UiOwners(isComplete = true, owners = emptyList())
        val loading = ServiceUiState.Loading(owners = mapOf("harness" to gone))

        val resolution = Spec.assertTransition(
            from = loading,
            intent = Internal.Loaded(Library),
            to = purging(ServiceUiState.Ready(owners = mapOf("harness" to gone), library = Library)),
            effects = listOf(ServiceUiEffect.Persist("purge.1", UiLibrary(revision = Library.revision + 1))),
        )
        assertEquals(
            setOf(StartSurface.id, PanelSurface.id),
            assertIs<ServiceUiState.Ready>(resolution.to).write!!.result.removed,
        )
    }

    @Test
    fun `an edit is saved before it is reported`() {
        val write = write("w1", put("other"))
        val applied = assertIs<UiWriteResult.Applied>(Library.apply(write.op))
        val saving = Ready.copy(write = UiPendingWrite(write, applied))

        Spec.assertTransition(
            from = Ready,
            intent = Internal.Edit(write),
            to = saving,
            effects = listOf(ServiceUiEffect.Persist("w1", applied.library)),
        )
        Spec.assertTransition(
            from = saving,
            intent = Internal.Persisted("w1"),
            to = Ready.copy(library = applied.library),
            outputs = listOf(ServiceUiOutput.EditApplied("w1", applied.surface)),
        )
        Spec.assertTransition(
            from = saving,
            intent = Internal.PersistFailed("w1"),
            to = Ready,
            outputs = listOf(ServiceUiOutput.EditRejected("w1", UiEditRejection.Storage)),
        )
        Spec.assertIgnored(saving, Internal.Persisted("stale"))
        Spec.assertIgnored(saving, Internal.PersistFailed("stale"))
        Spec.assertTransition(
            from = saving,
            intent = Internal.Edit(write("w2", put("third"))),
            to = saving,
            outputs = listOf(ServiceUiOutput.EditRejected("w2", UiEditRejection.Busy)),
        )
    }

    @Test
    fun `rejected and empty edits are reported at once`() {
        Spec.assertTransition(
            from = Ready,
            intent = Internal.Edit(write("w1", put("map"))),
            to = Ready,
            outputs = listOf(ServiceUiOutput.EditRejected("w1", UiEditRejection.Conflict)),
        )
        Spec.assertTransition(
            from = Ready,
            intent = Internal.Edit(write("w2", UiWriteOp.Delete(content("nothing").id, 1))),
            to = Ready,
            outputs = listOf(ServiceUiOutput.EditRejected("w2", UiEditRejection.Missing)),
        )
        Spec.assertTransition(
            from = Ready,
            intent = Internal.Edit(write("w3", UiWriteOp.Purge(setOf(UiOwner("harness", "other"))))),
            to = Ready,
            outputs = listOf(ServiceUiOutput.EditApplied("w3", null)),
        )
    }

    @Test
    fun `owner deletion waits for the write being saved`() {
        val write = write("w1", put("other"))
        val applied = assertIs<UiWriteResult.Applied>(Library.apply(write.op))
        val saving = Ready.copy(write = UiPendingWrite(write, applied))
        val gone = UiOwners(isComplete = true)

        val waiting = Spec.assertTransition(
            from = saving,
            intent = Internal.OwnersChanged("harness", gone),
            to = saving.copy(owners = mapOf("harness" to gone), purge = setOf(Owner)),
        ).to
        val purge = assertIs<UiWriteResult.Applied>(applied.library.apply(UiWriteOp.Purge(setOf(Owner))))
        Spec.assertTransition(
            from = waiting,
            intent = Internal.Persisted("w1"),
            to = Ready.copy(
                owners = mapOf("harness" to gone),
                library = applied.library,
                write = UiPendingWrite(UiWrite("purge.1", UiWriteOrigin.System, UiWriteOp.Purge(setOf(Owner))), purge),
                writes = 1,
            ),
            effects = listOf(ServiceUiEffect.Persist("purge.1", purge.library)),
            outputs = listOf(ServiceUiOutput.EditApplied("w1", applied.surface)),
        )
    }

    @Test
    fun `incomplete or listing snapshots purge nothing`() {
        val incomplete = UiOwners(isComplete = false)
        val listing = UiOwners(isComplete = true, owners = listOf(UiOwnerInfo(Owner.id, "pr", "PR", isEnabled = false)))

        Spec.assertTransition(Ready, Internal.OwnersChanged("harness", incomplete), Ready.owned(incomplete))
        Spec.assertTransition(Ready, Internal.OwnersChanged("harness", listing), Ready.owned(listing))
        Spec.assertTransition(
            from = Ready,
            intent = Internal.OwnersChanged("other", UiOwners(isComplete = true)),
            to = Ready.copy(owners = mapOf("other" to UiOwners(isComplete = true))),
        )
    }

    @Test
    fun `panels open in the dock of the place and close`() {
        val args = JsonObject(mapOf("pr" to JsonPrimitive(7)))
        val opened = Spec.assertTransition(
            from = Ready,
            intent = Public.OpenPanel(Host, PanelSurface.id, args),
            to = Ready.copy(panels = mapOf(Dock to UiPanel(PanelSurface.id, Host, args, 1)), panelSequence = 1),
        ).to
        val replaced = Spec.assertTransition(
            from = opened,
            intent = Public.OpenPanel(Host, PanelSurface.id),
            to = Ready.copy(panels = mapOf(Dock to UiPanel(PanelSurface.id, Host, sequence = 2)), panelSequence = 2),
        ).to
        Spec.assertTransition(replaced, Public.ClosePanel(Dock), Ready.copy(panelSequence = 2))
        Spec.assertIgnored(Ready, Public.ClosePanel(Dock))
        Spec.assertTransition(
            from = opened,
            intent = Public.UnbindHost(Host),
            to = Ready.copy(hosts = emptyMap(), panelSequence = 1),
        )
    }

    @Test
    fun `panels need a docked place, an enabled panel surface and bounded arguments`() {
        val undocked = Ready.copy(hosts = mapOf(Host to UiHostContext()))
        val disable = UiWriteOp.SetEnabled(PanelSurface.id, false, 1, At)
        val disabled = assertIs<UiWriteResult.Applied>(Library.apply(disable))
        val big = JsonObject(mapOf("text" to JsonPrimitive("x".repeat(ServiceUiLimits.ARGS_BYTES))))
        val crowded = Ready.copy(
            panels = (0 until ServiceUiLimits.PANELS).associate {
                UiDockId("d$it") to UiPanel(PanelSurface.id, Host, sequence = 1)
            },
        )

        Spec.assertIgnored(Ready, Public.OpenPanel(UiHostId("unknown"), PanelSurface.id))
        Spec.assertIgnored(undocked, Public.OpenPanel(Host, PanelSurface.id))
        Spec.assertIgnored(Ready, Public.OpenPanel(Host, StartSurface.id))
        Spec.assertIgnored(Ready.copy(library = disabled.library), Public.OpenPanel(Host, PanelSurface.id))
        Spec.assertIgnored(Ready, Public.OpenPanel(Host, PanelSurface.id, big))
        Spec.assertIgnored(crowded, Public.OpenPanel(Host, PanelSurface.id))
        Spec.assertIgnored(ServiceUiState.Loading(Hosts), Public.OpenPanel(Host, PanelSurface.id))
    }

    @Test
    fun `a commit closes panels of surfaces it disables`() {
        val open = Ready.copy(panels = mapOf(Dock to UiPanel(PanelSurface.id, Host, sequence = 1)), panelSequence = 1)
        val write = write("w1", UiWriteOp.SetEnabled(PanelSurface.id, false, 1, At))
        val applied = assertIs<UiWriteResult.Applied>(Library.apply(write.op))

        Spec.assertTransition(
            from = open.copy(write = UiPendingWrite(write, applied)),
            intent = Internal.Persisted("w1"),
            to = open.copy(library = applied.library, panels = emptyMap()),
            outputs = listOf(ServiceUiOutput.EditApplied("w1", applied.surface)),
        )
    }

    @Test
    fun `prompt requests from bound places reach the host`() {
        Spec.assertTransition(
            from = Ready,
            intent = Internal.RequestPrompt(Host, "Review PR 7", isSubmitted = true),
            to = Ready,
            outputs = listOf(
                ServiceUiOutput.PromptRequested(
                    Host,
                    "Review PR 7",
                    isSubmitted = true,
                    context = Hosts.getValue(Host),
                ),
            ),
        )
        Spec.assertIgnored(Ready, Internal.RequestPrompt(UiHostId("unknown"), "text", isSubmitted = false))
        Spec.assertIgnored(Ready, Internal.RequestPrompt(Host, " ", isSubmitted = false))
        Spec.assertIgnored(Ready, Internal.RequestPrompt(Host, "x".repeat(ServiceUiLimits.PROMPT_CHARS + 1), false))
    }

    @Test
    fun `queued surface actions are refused after their host context changes`() {
        val original = Hosts.getValue(Host)
        val changed = original.copy(call = UiCallContext(preselected = mapOf("harness" to setOf("other"))))
        val rebound = Ready.copy(hosts = mapOf(Host to changed))
        val prompt = Internal.RequestPrompt(Host, "Review PR 7", isSubmitted = true, expectedContext = original)
        Spec.assertIgnored(rebound, prompt)
        Spec.assertIgnored(rebound, Public.OpenPanel(Host, PanelSurface.id, expectedContext = original))
        Spec.assertTransition(
            from = Ready,
            intent = prompt,
            to = Ready,
            outputs = listOf(ServiceUiOutput.PromptRequested(Host, "Review PR 7", true, original)),
        )
    }

    @Test
    fun `disabling drops the library and reports the write being saved`() {
        val write = write("w1", put("other"))
        val saving = Ready.copy(write = UiPendingWrite(write, assertIs<UiWriteResult.Applied>(Library.apply(write.op))))

        Spec.assertTransition(
            from = saving,
            intent = Internal.AvailabilityChanged(false),
            to = ServiceUiState.Disabled(Hosts),
            outputs = listOf(ServiceUiOutput.EditRejected("w1", UiEditRejection.Unavailable)),
        )
        Spec.assertTransition(
            from = ServiceUiState.Failed(Hosts, attempt = 1),
            intent = Internal.AvailabilityChanged(false),
            to = ServiceUiState.Disabled(Hosts),
        )
    }

    @Test
    fun `effect failures map to storage results`() {
        assertEquals(Internal.LoadFailed, Spec.onEffectFailure(ServiceUiEffect.Load, IllegalStateException()))
        assertEquals(
            Internal.PersistFailed("w1"),
            Spec.onEffectFailure(ServiceUiEffect.Persist("w1", Library), IllegalStateException()),
        )
        assertEquals(null, Spec.onEffectFailure(ServiceUiEffect.RetryLoad(1), IllegalStateException()))
    }

    private fun ServiceUiState.bound(host: UiHostId, context: UiHostContext = UiHostContext()): ServiceUiState =
        when (this) {
            is ServiceUiState.Disabled -> copy(hosts = hosts + (host to context))
            is ServiceUiState.Loading -> copy(hosts = hosts + (host to context))
            is ServiceUiState.Failed -> copy(hosts = hosts + (host to context))
            is ServiceUiState.Ready -> copy(hosts = hosts + (host to context))
        }

    private fun ServiceUiState.owned(kind: String, owners: UiOwners): ServiceUiState = when (this) {
        is ServiceUiState.Disabled -> copy(owners = mapOf(kind to owners))
        is ServiceUiState.Loading -> copy(owners = mapOf(kind to owners))
        is ServiceUiState.Failed -> copy(owners = mapOf(kind to owners))
        is ServiceUiState.Ready -> copy(owners = mapOf(kind to owners))
    }

    private fun ServiceUiState.Ready.owned(owners: UiOwners) = copy(owners = mapOf("harness" to owners))

    private fun purging(ready: ServiceUiState.Ready): ServiceUiState.Ready {
        val write = UiWrite("purge.1", UiWriteOrigin.System, UiWriteOp.Purge(setOf(Owner)))
        val result = assertIs<UiWriteResult.Applied>(ready.library.apply(write.op))
        return ready.copy(write = UiPendingWrite(write, result), writes = 1)
    }

    private fun write(id: String, op: UiWriteOp) = UiWrite(id, UiWriteOrigin.Agent, op)

    private fun put(name: String) = UiWriteOp.Put(content(name), expectedRevision = 0, author = null, at = At)

    private companion object {
        val Spec = ServiceUiMachineSpec
        val At: Instant = Instant.fromEpochSeconds(1_700_000_000)
        val Owner = UiOwner("harness", "h_1")
        val Host = UiHostId("aistudio.pane.1")
        val Dock = UiDockId("aistudio")
        val Hosts = mapOf(Host to UiHostContext(UiCallContext(), Dock))
        val StartSurface = content("map")
        val PanelSurface = content("details").copy(placement = UiPlacement.Panel)
        val Library: UiLibrary = listOf(StartSurface, PanelSurface).fold(UiLibrary()) { library, content ->
            (library.apply(UiWriteOp.Put(content, 0, null, At)) as UiWriteResult.Applied).library
        }
        val Ready = ServiceUiState.Ready(Hosts, library = Library)
        val Complete = UiOwners(isComplete = true, owners = listOf(UiOwnerInfo(Owner.id, "pr", "PR", isEnabled = true)))

        fun content(name: String) = UiSurfaceContent(
            owner = Owner,
            name = UiSurfaceName(name),
            placement = UiPlacement.Start,
            title = "Title",
            spec = """{"version":1}""",
        )
    }
}
