package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiEditRejection
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwnerInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOp
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.FakeServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.TestScopeHandle
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.Other
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.surface
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.subscribe
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.testStores
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LibraryModelTest {
    private val map = surface("map", UiPlacement.Panel, revision = 3)
    private val quick = surface("quick", UiPlacement.Composer, isEnabled = false)
    private val notes = surface("notes", UiPlacement.Modal, owner = Other)
    private val owners = mapOf(
        "harness" to UiOwners(isComplete = true, listOf(UiOwnerInfo("h_1", "pr_map", "PR map", isEnabled = true))),
    )

    @Test
    fun `surfaces are grouped by owner with the owner's title and state`() = runTest {
        val fixture = Fixture(this, ready())
        val library = subscribe(fixture.model.store)

        val (unknown, known) = library.states.value.groups
        assertEquals(LibraryPhase.Ready, library.states.value.phase)
        assertEquals(OwnerGroupUi("harness:h_2", "h_2", isKnown = false, isEnabled = false, unknown.surfaces), unknown)
        assertEquals("PR map", known.title)
        assertTrue(known.isKnown && known.isEnabled)
        assertEquals(listOf("MAP", "QUICK"), known.surfaces.map { it.title })
        assertEquals(PlacementUi.Composer, known.surfaces[1].placement)
        assertEquals(false, known.surfaces[1].isEnabled)
    }

    @Test
    fun `the phase follows the machine and a failed library is read again on request`() = runTest {
        val fixture = Fixture(this, ServiceUiState.Disabled())
        val library = subscribe(fixture.model.store)
        assertEquals(LibraryPhase.Disabled, library.states.value.phase)

        fixture.machine.state.value = ServiceUiState.Failed(attempt = 3)
        runCurrent()
        assertEquals(LibraryPhase.Failed, library.states.value.phase)

        library.intent(LibraryIntent.Reload)
        runCurrent()
        assertEquals(listOf<ServiceUiIntent>(ServiceUiIntent.Public.Reload), fixture.machine.sent)
    }

    @Test
    fun `switching a surface is a user write at its revision`() = runTest {
        val fixture = Fixture(this, ready())
        val library = subscribe(fixture.model.store)

        library.intent(LibraryIntent.SetEnabled(map.id.value, isEnabled = false))
        runCurrent()

        val write = assertIs<ServiceUiIntent.Internal.Edit>(fixture.machine.sent.single()).write
        assertEquals(UiWriteOrigin.User, write.origin)
        assertEquals(UiWriteOp.SetEnabled(map.id, isEnabled = false, expectedRevision = 3, NOW), write.op)
        assertEquals(LibraryState(LibraryPhase.Ready, library.states.value.groups), library.states.value)
    }

    @Test
    fun `a rejected switch tells why and an unchanged one writes nothing`() = runTest {
        val fixture = Fixture(this, ready())
        fixture.machine.answer = { ServiceUiOutput.EditRejected(it.id, UiEditRejection.Conflict) }
        val library = subscribe(fixture.model.store)

        library.intent(LibraryIntent.SetEnabled(map.id.value, isEnabled = true))
        runCurrent()
        assertEquals(emptyList(), fixture.machine.sent)

        library.intent(LibraryIntent.SetEnabled(map.id.value, isEnabled = false))
        runCurrent()
        assertEquals(WriteNoticeUi.Changed, library.states.value.notice)
        assertEquals(emptySet(), library.states.value.pending)

        library.intent(LibraryIntent.DismissNotice)
        runCurrent()
        assertEquals(null, library.states.value.notice)
    }

    @Test
    fun `opening a surface asks the component to navigate`() = runTest {
        val fixture = Fixture(this, ready())
        val actions = mutableListOf<LibraryAction>()
        val library = subscribe(fixture.model.store, actions)

        library.intent(LibraryIntent.Open(notes.id.value))
        runCurrent()
        assertEquals(listOf<LibraryAction>(LibraryAction.OpenSurface(notes.id.value)), actions)
    }

    private fun ready() = ServiceUiState.Ready(owners = owners, library = UiLibrary(listOf(quick, notes, map)))

    private class Fixture(scope: TestScope, state: ServiceUiState) {
        val machine = FakeServiceUiMachine(state)
        val model = LibraryModel(
            machine,
            object : Clock {
                override fun now(): Instant = NOW
            },
            testStores(scope),
            TestScopeHandle(scope.backgroundScope),
        )
    }

    private companion object {
        val NOW: Instant = Instant.fromEpochSeconds(100)
    }
}
