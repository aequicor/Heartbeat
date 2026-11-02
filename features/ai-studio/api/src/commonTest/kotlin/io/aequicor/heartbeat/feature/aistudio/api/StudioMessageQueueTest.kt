package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioMessageQueueTest {
    private val settings = RunSettings("model", ReasoningEffort.High, ApprovalMode.Ask)
    private val first = StudioQueuedMessage("first", "First", emptyList(), settings)
    private val second = first.copy(id = "second", text = "Second")
    private val data = StudioMessageQueues(mapOf("chat" to StudioChatQueue(listOf(first, second))))
    private val spec = studioQueueMachineSpec(data)

    @Test
    fun `creator input joins FIFO and its complete envelope survives acceptance and restart`() {
        val creator = first.copy(
            id = "creator",
            origin = StudioQueuedOrigin.Creator(
                LogicalSessionId("owner"),
                SessionRef(EngineId("engine"), SessionSourceId("source"), "native"),
                RequestId("cause"),
            ),
            directives = listOf("Agent origin"),
        )
        val initial = StudioQueueState(data)
        val captured = data.withChat(
            "chat",
            data.chat("chat").copy(
                messages = listOf(first, second, creator),
                creatorMessages = mapOf(creator.id to creator),
            ),
        )
        spec.assertTransition(
            initial,
            StudioQueueIntent.Public.Enqueue("chat", creator, "capture"),
            initial.copy(data = captured, saving = "capture"),
            effects = listOf(StudioQueueEffect.Save("capture", captured)),
        )
        val active = captured.withChat(
            "chat",
            captured.chat("chat").copy(
                messages = listOf(creator.copy(delivery = StudioQueuedDelivery.Sending)),
                activeRequest = creator.id,
            ),
        )
        val accepted = active.withChat("chat", active.chat("chat").copy(messages = emptyList()))
        spec.assertTransition(
            StudioQueueState(active),
            StudioQueueIntent.Internal.Accepted("chat", creator.id, "accepted"),
            StudioQueueState(active).copy(data = accepted, saving = "accepted"),
            effects = listOf(StudioQueueEffect.Save("accepted", accepted)),
        )
        val restored = Json.decodeFromString<StudioMessageQueues>(Json.encodeToString(accepted))
        assertEquals(creator, restored.chat("chat").restored().creatorMessages[creator.id])
        spec.assertIgnored(StudioQueueState(restored), StudioQueueIntent.Public.Enqueue("chat", creator, "replay"))
        val busy = StudioQueueState(captured, busy = setOf("chat"), steerable = setOf("chat"))
        spec.assertIgnored(busy, StudioQueueIntent.Public.Steer("chat", creator.id, "steer"))
        spec.assertIgnored(
            busy,
            StudioQueueIntent.Public.Interrupt("chat", StudioCorrection(creator.id, "token", "active"), "stop"),
        )
        val fenced = active.withChat("chat", active.chat("chat").copy(messages = emptyList(), activeRequest = null))
        spec.assertTransition(
            StudioQueueState(active),
            StudioQueueIntent.Public.RetireFenced("chat", creator.id, "cancel"),
            StudioQueueState(active).copy(data = fenced, saving = "cancel"),
            effects = listOf(StudioQueueEffect.Save("cancel", fenced)),
        )
        assertEquals(creator, fenced.chat("chat").creatorMessages[creator.id])
    }

    @Test
    fun `interrupt confirmation waits for idle then dispatches the selected correction`() {
        val correction = StudioCorrection(second.id, "cancel-one", "original")
        val reserved = data.withChat("chat", data.chat("chat").copy(correction = correction))
        val busy = StudioQueueState(reserved, busy = setOf("chat"))
        spec.assertIgnored(busy, StudioQueueIntent.Public.Drain("chat", "drain"))
        val confirmed = reserved.withChat(
            "chat",
            reserved.chat("chat").copy(correction = correction.copy(isReady = true)),
        )
        spec.assertTransition(
            busy,
            StudioQueueIntent.Internal.Interrupted("chat", correction.token, "confirmed"),
            busy.copy(data = confirmed, saving = "confirmed"),
            effects = listOf(StudioQueueEffect.Save("confirmed", confirmed)),
        )
        spec.assertIgnored(busy.copy(data = confirmed), StudioQueueIntent.Public.Drain("chat", "drain"))
        val idle = StudioQueueState(confirmed)
        val sending = confirmed.withChat(
            "chat",
            confirmed.chat("chat").copy(
                correction = null,
                activeRequest = second.id,
                messages = listOf(first, second.copy(delivery = StudioQueuedDelivery.Sending)),
            ),
        )
        spec.assertTransition(
            idle,
            StudioQueueIntent.Public.Drain("chat", "drain"),
            idle.copy(data = sending, saving = "drain"),
            effects = listOf(StudioQueueEffect.Save("drain", sending, StudioQueueEffect.Dispatch("chat", second))),
        )
    }

    @Test
    fun `manual stop revokes correction and late confirmation cannot revive it`() {
        val correction = StudioCorrection(second.id, "old", "original")
        val reserved = data.withChat("chat", data.chat("chat").copy(correction = correction))
        val initial = StudioQueueState(reserved)
        val paused = reserved.withChat("chat", reserved.chat("chat").copy(correction = null, isPaused = true))
        spec.assertTransition(
            initial,
            StudioQueueIntent.Public.Pause("chat", "stop"),
            initial.copy(data = paused, saving = "stop"),
            effects = listOf(StudioQueueEffect.Save("stop", paused)),
        )
        val late = StudioQueueIntent.Internal.Interrupted("chat", "old", "late")
        spec.assertIgnored(StudioQueueState(paused), late)
        val replaced = reserved.withChat(
            "chat",
            reserved.chat("chat").copy(correction = correction.copy(token = "new")),
        )
        spec.assertIgnored(StudioQueueState(replaced), late)
        assertEquals(null, reserved.chat("chat").restored().correction)
        assertEquals(listOf(first, second), reserved.chat("chat").restored().messages)
    }

    @Test
    fun `enqueue captures input durably and refuses invalid or duplicate messages`() {
        val initial = StudioQueueState(StudioMessageQueues())
        val captured = StudioMessageQueues(mapOf("chat" to StudioChatQueue(listOf(first))))
        val enqueue = StudioQueueIntent.Public.Enqueue("chat", first, "capture")
        spec.assertTransition(
            initial,
            enqueue,
            initial.copy(data = captured, saving = "capture"),
            effects = listOf(StudioQueueEffect.Save("capture", captured)),
        )
        spec.assertIgnored(StudioQueueState(captured), enqueue)
        spec.assertIgnored(initial.copy(isEnabled = false), enqueue)
        spec.assertIgnored(initial.copy(saving = "other"), enqueue)
        spec.assertIgnored(initial, enqueue.copy(message = first.copy(id = "")))
        spec.assertIgnored(initial, enqueue.copy(message = first.copy(text = " ")))
    }

    @Test
    fun `draining waits for an unresolved correction or uncertain message anywhere in the queue`() {
        val unresolvedDeliveries = listOf(
            StudioQueuedDelivery.Sending,
            StudioQueuedDelivery.Steering,
            StudioQueuedDelivery.Uncertain,
        )
        for (delivery in unresolvedDeliveries) {
            val unresolved = data.withChat(
                "chat",
                StudioChatQueue(listOf(first, second.copy(delivery = delivery))),
            )
            spec.assertIgnored(StudioQueueState(unresolved), StudioQueueIntent.Public.Drain("chat", "next"))
        }
    }

    @Test
    fun `unknown correction retains its input and pauses automatic delivery`() {
        val inFlight = data.withChat(
            "chat",
            StudioChatQueue(
                listOf(first, second.copy(delivery = StudioQueuedDelivery.Steering)),
                activeRequest = "running",
            ),
        )
        val retained = data.withChat(
            "chat",
            StudioChatQueue(
                listOf(first, second.copy(delivery = StudioQueuedDelivery.Uncertain)),
                isPaused = true,
                activeRequest = "running",
            ),
        )
        val initial = StudioQueueState(inFlight)
        val failed = StudioQueueIntent.Internal.Failed("chat", "second", true, "unknown")
        spec.assertTransition(
            initial,
            failed,
            initial.copy(data = retained, saving = "unknown"),
            effects = listOf(StudioQueueEffect.Save("unknown", retained)),
        )
        spec.assertIgnored(initial.copy(saving = "other"), failed)
    }

    @Test
    fun `definitely rejected correction returns to pending without changing the running turn owner`() {
        val inFlight = data.withChat(
            "chat",
            StudioChatQueue(
                listOf(first, second.copy(delivery = StudioQueuedDelivery.Steering)),
                activeRequest = "running",
            ),
        )
        val retained = data.withChat("chat", StudioChatQueue(listOf(first, second), activeRequest = "running"))
        val initial = StudioQueueState(inFlight)
        spec.assertTransition(
            initial,
            StudioQueueIntent.Internal.Failed("chat", "second", false, "rejected"),
            initial.copy(data = retained, saving = "rejected"),
            effects = listOf(StudioQueueEffect.Save("rejected", retained)),
        )
    }

    @Test
    fun `resume persists the choice while uncertain input still prevents draining`() {
        val paused = data.withChat(
            "chat",
            StudioChatQueue(listOf(first, second.copy(delivery = StudioQueuedDelivery.Uncertain)), isPaused = true),
        )
        val resumed = paused.withChat("chat", paused.chat("chat").copy(isPaused = false))
        val initial = StudioQueueState(paused)
        val resume = StudioQueueIntent.Public.Resume("chat", "resume")
        spec.assertTransition(
            initial,
            resume,
            initial.copy(data = resumed, saving = "resume"),
            effects = listOf(StudioQueueEffect.Save("resume", resumed)),
        )
        spec.assertIgnored(initial.copy(saving = "other"), resume)
        spec.assertIgnored(StudioQueueState(resumed), StudioQueueIntent.Public.Drain("chat", "next"))
    }

    @Test
    fun `activity and feature availability wake the queue only outside a pending write`() {
        val initial = StudioQueueState(data)
        val activity = StudioQueueIntent.Internal.Activity(setOf("chat"), setOf("chat"))
        spec.assertTransition(
            initial,
            activity,
            initial.copy(busy = setOf("chat"), steerable = setOf("chat")),
            effects = listOf(StudioQueueEffect.Wake),
        )
        spec.assertIgnored(initial.copy(saving = "capture"), activity)
        val availability = StudioQueueIntent.Internal.Availability(false)
        spec.assertTransition(
            initial,
            availability,
            initial.copy(isEnabled = false),
            effects = listOf(StudioQueueEffect.Wake),
        )
        spec.assertIgnored(initial.copy(saving = "capture"), availability)
    }

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
