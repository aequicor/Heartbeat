package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiApp
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiDockId
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiPanel
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaceSelectionTest {
    private val host = UiHostId("pane-1")
    private val dock = UiDockId("side")
    private val context = UiHostContext(UiCallContext(preselected = mapOf("harness" to setOf("h_1"))), dock)
    private val start = surface("start", UiPlacement.Start, at = 2)
    private val first = surface("first", UiPlacement.Start, at = 1)
    private val map = surface("map", UiPlacement.Panel, launchers = setOf(UiSlot.Start, UiSlot.Composer))
    private val details = surface("details", UiPlacement.Modal)
    private val ready = ServiceUiState.Ready(
        hosts = mapOf(host to context),
        library = UiLibrary(
            listOf(
                start,
                first,
                map,
                details,
                surface("composer", UiPlacement.Composer),
                surface("off", UiPlacement.Start, isEnabled = false),
                surface("foreign", UiPlacement.Start, owner = Other),
            ),
        ),
    )

    @Test
    fun `the canonical app absorbs only its own old start and composer surfaces`() {
        val app = surface("app", UiPlacement.Card)
        val selected = ready.copy(
            library = ready.library.copy(surfaces = ready.library.surfaces + app),
            apps = mapOf(host to UiApp(app.id, context)),
        )
        val startContent = PlaceTarget.Slot(host, UiSlot.Start).content(selected, setOf(PrMap, Other))
        assertEquals(listOf("foreign"), startContent.surfaces.map { it.surface.content.name.value })
        assertEquals(emptyList(), PlaceTarget.Slot(host, UiSlot.Composer).content(selected, setOf(PrMap)).surfaces)
        assertEquals(listOf(map), startContent.launchers, "Unrelated explicit panel launchers keep their contract")
    }

    @Test
    fun `a slot shows enabled surfaces of its placement and launchers of active owners oldest first`() {
        val content = PlaceTarget.Slot(host, UiSlot.Start).content(ready, setOf(PrMap))

        assertEquals(
            listOf(
                PlacedSurface(PlaceRole.Body, first, host, context),
                PlacedSurface(PlaceRole.Body, start, host, context),
            ),
            content.surfaces,
        )
        assertEquals(listOf(map), content.launchers)
        assertEquals(host, content.host)
    }

    @Test
    fun `nothing shows before the library is ready the place is bound or an owner is active`() {
        val slot = PlaceTarget.Slot(host, UiSlot.Start)
        assertEquals(PlaceContent(), slot.content(ready.copy(hosts = emptyMap()), setOf(PrMap)))
        assertNull(slot.host(ServiceUiState.Disabled(hosts = ready.hosts)))
        assertEquals(PlaceContent(), slot.content(ServiceUiState.Loading(hosts = ready.hosts), setOf(PrMap)))
        val inactive = slot.content(ready, emptySet())
        assertEquals(emptyList(), inactive.surfaces)
        assertEquals(emptyList(), inactive.launchers)
    }

    @Test
    fun `a panel shows the surface open in its dock while its owner is active`() {
        val args = JsonObject(mapOf("pr" to JsonPrimitive(7)))
        val open = ready.copy(panels = mapOf(dock to UiPanel(map.id, host, args, sequence = 1)))
        val panel = PlaceTarget.Panel(dock)

        val content = panel.content(open, setOf(PrMap))
        assertEquals(listOf(PlacedSurface(PlaceRole.Body, map, host, context, args)), content.surfaces)
        assertEquals("MAP", content.panelTitle)
        assertEquals(emptyList(), panel.content(open, emptySet()).surfaces)
        assertEquals(PlaceContent(), PlaceTarget.Panel(UiDockId("other")).content(open, setOf(PrMap)))
    }

    @Test
    fun `a modal follows its surface and the context of its place and leaves with them`() {
        val slot = PlaceTarget.Slot(host, UiSlot.Start)
        val open = PlacedSurface(PlaceRole.Modal, details, host, context)
        val active = setOf(PrMap)

        assertEquals(open, slot.modal(ready, active, open))
        val revised = details.copy(revision = 2)
        val rebound = UiHostContext(UiCallContext())
        val changed = ready.copy(
            hosts = mapOf(host to rebound),
            library = UiLibrary(ready.library.surfaces.map { if (it.id == details.id) revised else it }),
        )
        assertEquals(open.copy(surface = revised, context = rebound), slot.modal(changed, active, open))

        assertNull(slot.modal(ready, emptySet(), open))
        assertNull(slot.modal(ready.copy(hosts = emptyMap()), active, open))
        val disabled = UiLibrary(
            ready.library.surfaces.map { if (it.id == details.id) it.copy(isEnabled = false) else it },
        )
        assertNull(slot.modal(ready.copy(library = disabled), active, open))
        assertNull(slot.modal(ready, active, open.copy(surface = start)))
    }
}
