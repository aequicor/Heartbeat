package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioMessageQueueTest {
    private val settings = RunSettings("model", ReasoningEffort.High, ApprovalMode.Ask)
    private val first = StudioQueuedMessage("first", "First", emptyList(), settings)
    private val second = first.copy(id = "second", text = "Second")
    private val data = StudioMessageQueues(mapOf("chat" to StudioChatQueue(listOf(first, second))))
    private val spec = studioQueueMachineSpec(data)

    @Test
    fun `removing uncertain input releases a reservation that never reached native submission`() {
        val uncertain = data.withChat(
            "chat",
            StudioChatQueue(
                listOf(
                    first.copy(delivery = StudioQueuedDelivery.Uncertain),
                    second,
                ),
                isPaused = true,
                activeRequest = "first",
            ),
        )
        val initial = StudioQueueState(uncertain)
        val removed = data.withChat("chat", StudioChatQueue(listOf(second), isPaused = true))
        spec.assertTransition(
            initial,
            StudioQueueIntent.Public.Remove("chat", "first", "remove"),
            initial.copy(data = removed, saving = "remove"),
            effects = listOf(StudioQueueEffect.Save("remove", removed)),
        )
        val resumed = removed.withChat("chat", removed.chat("chat").copy(isPaused = false))
        val dispatching = resumed.withChat(
            "chat",
            StudioChatQueue(
                listOf(second.copy(delivery = StudioQueuedDelivery.Sending)),
                activeRequest = "second",
            ),
        )
        spec.assertTransition(
            StudioQueueState(resumed),
            StudioQueueIntent.Public.Drain("chat", "next"),
            StudioQueueState(dispatching, durable = resumed, saving = "next"),
            effects = listOf(StudioQueueEffect.Save("next", dispatching, StudioQueueEffect.Dispatch("chat", second))),
        )
    }

    @Test
    fun `preparation cancelled before native begin retains a definitely unsent pending message`() {
        val inflight = data.withChat(
            "chat",
            StudioChatQueue(
                listOf(
                    first.copy(delivery = StudioQueuedDelivery.Sending),
                    second,
                ),
                activeRequest = "first",
            ),
        )
        val safe = data.withChat("chat", StudioChatQueue(listOf(first, second), isPaused = true))
        spec.assertTransition(
            StudioQueueState(inflight),
            StudioQueueIntent.Internal.Settled("chat", "first", RunOutcome.Failed, "stop", isSubmitted = false),
            StudioQueueState(safe, durable = inflight, saving = "stop"),
            effects = listOf(StudioQueueEffect.Save("stop", safe)),
        )
    }

    @Test
    fun `FIFO dispatch is gated by durable write and authoritative reservation`() {
        val initial = StudioQueueState(data)
        val sending = data.withChat(
            "chat",
            data.chat("chat").copy(
                activeRequest = "first",
                messages = listOf(first.copy(delivery = StudioQueuedDelivery.Sending), second),
            ),
        )
        val waiting = initial.copy(data = sending, saving = "op")
        val next = StudioQueueEffect.Dispatch("chat", first)
        spec.assertTransition(
            initial,
            StudioQueueIntent.Public.Drain("chat", "op"),
            waiting,
            effects = listOf(StudioQueueEffect.Save("op", sending, next)),
        )
        spec.assertIgnored(initial.copy(busy = setOf("chat")), StudioQueueIntent.Public.Drain("chat", "op"))
        spec.assertIgnored(waiting, StudioQueueIntent.Public.Drain("chat", "other"))
        spec.assertTransition(
            waiting,
            StudioQueueIntent.Internal.Stored("op", next),
            waiting.copy(durable = sending, saving = null),
            effects = listOf(next),
            outputs = listOf(StudioQueueOutput("op", true)),
        )
    }

    @Test
    fun `steering accepts exactly selected row and preserves FIFO execution owner`() {
        val initial = StudioQueueState(data, busy = setOf("chat"), steerable = setOf("chat"))
        val updated = data.withChat(
            "chat",
            data.chat("chat").copy(
                messages = listOf(first, second.copy(delivery = StudioQueuedDelivery.Steering)),
            ),
        )
        spec.assertTransition(
            initial,
            StudioQueueIntent.Public.Steer("chat", "second", "op"),
            initial.copy(data = updated, saving = "op"),
            effects = listOf(StudioQueueEffect.Save("op", updated, StudioQueueEffect.Steer("chat", second))),
        )
        val steering = initial.copy(data = updated, durable = updated)
        val accepted = data.withChat("chat", StudioChatQueue(listOf(first)))
        spec.assertTransition(
            steering,
            StudioQueueIntent.Internal.Accepted("chat", "second", "ack"),
            steering.copy(data = accepted, saving = "ack"),
            effects = listOf(StudioQueueEffect.Save("ack", accepted)),
        )
        spec.assertIgnored(initial, StudioQueueIntent.Internal.Accepted("chat", "second", "stale"))
    }

    @Test
    fun `stop pauses without deleting input and uncertain head is never replayed`() {
        val paused = data.withChat("chat", data.chat("chat").copy(isPaused = true))
        val initial = StudioQueueState(data)
        spec.assertTransition(
            initial,
            StudioQueueIntent.Public.Pause("chat", "stop"),
            initial.copy(data = paused, saving = "stop"),
            effects = listOf(StudioQueueEffect.Save("stop", paused)),
        )
        spec.assertIgnored(StudioQueueState(paused), StudioQueueIntent.Public.Drain("chat", "drain"))
        val unknown = data.withChat(
            "chat",
            StudioChatQueue(
                listOf(
                    first.copy(delivery = StudioQueuedDelivery.Uncertain),
                    second,
                ),
            ),
        )
        spec.assertIgnored(StudioQueueState(unknown), StudioQueueIntent.Public.Drain("chat", "drain"))
        spec.assertIgnored(
            StudioQueueState(unknown, busy = setOf("chat"), steerable = setOf("chat")),
            StudioQueueIntent.Public.Steer("chat", "second", "steer"),
        )
        spec.assertIgnored(initial.copy(isEnabled = false), StudioQueueIntent.Public.Drain("chat", "off"))
    }

    @Test
    fun `journal failure after native acceptance exposes uncertainty and blocks automatic replay`() {
        val inflight = data.withChat(
            "chat",
            StudioChatQueue(
                listOf(
                    first.copy(delivery = StudioQueuedDelivery.Steering),
                    second,
                ),
            ),
        )
        val removed = data.withChat("chat", StudioChatQueue(listOf(second)))
        val initial = StudioQueueState(removed, durable = inflight, saving = "ack")
        val safe = data.withChat(
            "chat",
            StudioChatQueue(
                listOf(
                    first.copy(delivery = StudioQueuedDelivery.Uncertain),
                    second,
                ),
                isPaused = true,
            ),
        )
        spec.assertTransition(
            initial,
            StudioQueueIntent.Internal.StoreFailed("ack"),
            initial.copy(data = safe, durable = safe, saving = null),
            outputs = listOf(StudioQueueOutput("ack", false)),
        )
        assertEquals(
            StudioQueuedDelivery.Uncertain,
            inflight.chat("chat").restored().messages.first().delivery,
        )
    }
}
