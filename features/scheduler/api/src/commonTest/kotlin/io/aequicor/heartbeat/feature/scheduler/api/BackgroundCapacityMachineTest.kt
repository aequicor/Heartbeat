package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlin.test.Test

class BackgroundCapacityMachineTest {
    private val parent = SessionRef(EngineId("engine"), SessionSourceId("source"), "parent")
    private val first = helper("h1")

    @Test
    fun `a new helper reserves capacity immediately`() {
        BackgroundCapacityMachineSpec.assertTransition(
            BackgroundCapacityState.Ready(),
            BackgroundCapacityIntent.Public.Acquire(first),
            BackgroundCapacityState.Ready(active = listOf(first)),
            outputs = listOf(BackgroundCapacityOutput.Granted(listOf(first.id))),
        )
    }

    @Test
    fun `a duplicate active or queued acquisition never consumes another slot`() {
        for (state in listOf(
            BackgroundCapacityState.Ready(active = listOf(first)),
            BackgroundCapacityState.Ready(queued = listOf(first)),
        )) {
            BackgroundCapacityMachineSpec.assertTransition(
                state,
                BackgroundCapacityIntent.Public.Acquire(first),
                state,
                outputs = listOf(BackgroundCapacityOutput.Rejected(first.id, BackgroundCapacityRejection.Duplicate)),
            )
        }
    }

    @Test
    fun `helpers queue behind their owner limit even with profile room`() {
        val active = (1..4).map { helper("h$it") }
        val pending = helper("h5")
        BackgroundCapacityMachineSpec.assertTransition(
            BackgroundCapacityState.Ready(active),
            BackgroundCapacityIntent.Public.Acquire(pending),
            BackgroundCapacityState.Ready(active, listOf(pending)),
            outputs = listOf(BackgroundCapacityOutput.Queued(pending.id)),
        )
    }

    @Test
    fun `scheduled actions and helpers share the same eight slots`() {
        val active = (1..6).map { helper("h$it", owner = "run$it") } +
            listOf(scheduled("a1"), scheduled("a2"))
        val state = BackgroundCapacityState.Ready(active)
        val waiting = helper("h7", "next")
        BackgroundCapacityMachineSpec.assertTransition(
            state,
            BackgroundCapacityIntent.Public.Acquire(waiting),
            state.copy(queued = listOf(waiting)),
            outputs = listOf(BackgroundCapacityOutput.Queued(waiting.id)),
        )
        val refused = scheduled("a3")
        BackgroundCapacityMachineSpec.assertTransition(
            state,
            BackgroundCapacityIntent.Public.Acquire(refused),
            state,
            outputs = listOf(BackgroundCapacityOutput.Rejected(refused.id, BackgroundCapacityRejection.ProfileLimit)),
        )
    }

    @Test
    fun `scheduled actions retain three per parent without counting workflow helpers`() {
        val active = (1..3).map { scheduled("a$it") }
        val next = scheduled("a4")
        val state = BackgroundCapacityState.Ready(active)
        BackgroundCapacityMachineSpec.assertTransition(
            state,
            BackgroundCapacityIntent.Public.Acquire(next),
            state,
            outputs = listOf(BackgroundCapacityOutput.Rejected(next.id, BackgroundCapacityRejection.SessionLimit)),
        )
        BackgroundCapacityMachineSpec.assertTransition(
            state,
            BackgroundCapacityIntent.Public.Acquire(first),
            state.copy(active = active + first),
            outputs = listOf(BackgroundCapacityOutput.Granted(listOf(first.id))),
        )
    }

    @Test
    fun `release skips owner-blocked waiters and grants the next eligible workflow`() {
        val active = (1..4).map { helper("h$it") } + (1..4).map { helper("other$it", "other") }
        val blocked = helper("h5")
        val eligible = helper("next", "next")
        BackgroundCapacityMachineSpec.assertTransition(
            BackgroundCapacityState.Ready(active, listOf(blocked, eligible)),
            BackgroundCapacityIntent.Public.Release(active.last().id),
            BackgroundCapacityState.Ready(active.dropLast(1) + eligible, listOf(blocked)),
            outputs = listOf(BackgroundCapacityOutput.Granted(listOf(eligible.id))),
        )
    }

    @Test
    fun `release fills newly available owner capacity in arrival order`() {
        val active = (1..4).map { helper("h$it") }
        val queued = listOf(helper("h5"), helper("h6"))
        BackgroundCapacityMachineSpec.assertTransition(
            BackgroundCapacityState.Ready(active, queued),
            BackgroundCapacityIntent.Public.Release(active.first().id),
            BackgroundCapacityState.Ready(active.drop(1) + queued.first(), queued.drop(1)),
            outputs = listOf(BackgroundCapacityOutput.Granted(listOf(queued.first().id))),
        )
    }

    @Test
    fun `cancelling a queued acquisition does not release active capacity`() {
        val active = (1..4).map { helper("h$it") }
        val pending = helper("h5")
        BackgroundCapacityMachineSpec.assertTransition(
            BackgroundCapacityState.Ready(active, listOf(pending)),
            BackgroundCapacityIntent.Public.Release(pending.id),
            BackgroundCapacityState.Ready(active),
        )
    }

    @Test
    fun `repeated release is an accepted no-op`() {
        BackgroundCapacityMachineSpec.assertTransition(
            BackgroundCapacityState.Ready(active = listOf(first)),
            BackgroundCapacityIntent.Public.Release(ActionId("gone")),
            BackgroundCapacityState.Ready(active = listOf(first)),
        )
    }

    private fun helper(id: String, owner: String = "run") =
        BackgroundReservation(ActionId(id), ActionId(owner), parent, BackgroundCapacityKind.Helper)

    private fun scheduled(id: String) =
        BackgroundReservation(ActionId(id), ActionId(id), parent, BackgroundCapacityKind.Scheduled)
}
