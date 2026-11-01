package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.TransitionScope
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import kotlinx.serialization.Serializable

/** Human messages retained by the profile until delivery is acknowledged. */
public val StudioMessageQueueEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "ai_studio.message_queue",
    description = "Очередь сообщений чата и корректировка текущего хода",
    default = true,
)

/** Uncertain delivery must never be replayed automatically, including after restarting the profile. */
@Serializable
public enum class StudioQueuedDelivery { Pending, Sending, Steering, Uncertain }

/** Immutable human input; [id] is also its stable native request correlation id. */
@Serializable
public data class StudioQueuedMessage(
    val id: String,
    val text: String,
    val attachments: List<ResourceRef>,
    val settings: RunSettings,
    val userInstruction: String? = null,
    val hasEnvironment: Boolean = false,
    val delivery: StudioQueuedDelivery = StudioQueuedDelivery.Pending,
) {
    override fun toString(): String = "StudioQueuedMessage(id=$id, delivery=$delivery)"
}

/** One logical chat's FIFO. [activeRequest] retains execution ownership after its accepted row has disappeared. */
@Serializable
public data class StudioChatQueue(
    val messages: List<StudioQueuedMessage> = emptyList(),
    val isPaused: Boolean = false,
    val activeRequest: String? = null,
) {
    /** A restart preserves input but cannot infer whether an in-flight native operation was accepted. */
    public fun restored(): StudioChatQueue = copy(
        isPaused = true,
        activeRequest = null,
        messages = messages.map {
            if (it.delivery == StudioQueuedDelivery.Pending) it else it.copy(delivery = StudioQueuedDelivery.Uncertain)
        },
    )
}

/** Durable queue data, independent of screen panes and native session rollover. */
@Serializable
public data class StudioMessageQueues(val chats: Map<String, StudioChatQueue> = emptyMap()) {
    /** Returns an empty queue for a chat that has never enqueued input. */
    public fun chat(id: String): StudioChatQueue = chats[id] ?: StudioChatQueue()

    /** Replaces exactly one chat without mutating other queues. */
    public fun withChat(id: String, queue: StudioChatQueue): StudioMessageQueues = copy(chats = chats + (id to queue))
}

/** Queue commands are serialized by their profile owner until each persistence acknowledgement. */
public data class StudioQueueState(
    val data: StudioMessageQueues,
    val durable: StudioMessageQueues = data,
    val busy: Set<String> = emptySet(),
    val steerable: Set<String> = emptySet(),
    val saving: String? = null,
    val isEnabled: Boolean = true,
) : MachineState

/** Transient delivery feedback, independent of the durable queue and safe to dismiss. */
public data class StudioQueueNotice(val id: String, val isSuccess: Boolean, val isUncertain: Boolean = false)

/** Explicit actions on accepted queue rows and transient feedback. */
public enum class StudioQueueAction { Correct, Remove, Resume, DismissNotice }

/** Mutations of the queue, not mutations of a screen's draft. */
public sealed interface StudioQueueIntent : MachineIntent {
    /** Commands admitted by the profile owner. [operation] correlates the durable commit. */
    public sealed interface Public : StudioQueueIntent {
        /** Append one captured human message. */
        public data class Enqueue(val chat: String, val message: StudioQueuedMessage, val operation: String) : Public

        /** Delete a row that is not currently crossing a delivery boundary. */
        public data class Remove(val chat: String, val message: String, val operation: String) : Public

        /** Deliver exactly this pending row to the current native turn. */
        public data class Steer(val chat: String, val message: String, val operation: String) : Public

        /** Stop automatic continuation before requesting native cancellation. */
        public data class Pause(val chat: String, val operation: String) : Public

        /** Explicitly resume waiting human input. Uncertain rows still require removal or reconciliation. */
        public data class Resume(val chat: String, val operation: String) : Public

        /** Reconsider the first pending row after a reservation was released. */
        public data class Drain(val chat: String, val operation: String) : Public
    }

    /** Results emitted by persistence and native delivery. */
    public sealed interface Internal : StudioQueueIntent {
        /** Disabling the feature prevents automatic delivery while retaining existing input. */
        public data class Availability(val isEnabled: Boolean) : Internal

        /** The exact durable write completed. */
        public data class Stored(val operation: String, val next: StudioQueueEffect?) : Internal

        /** The write failed; restore the last committed snapshot before reporting refusal. */
        public data class StoreFailed(val operation: String) : Internal

        /** Native acceptance is the only event that removes a delivered row. */
        public data class Accepted(val chat: String, val message: String, val operation: String) : Internal

        /** A rejected or uncertain delivery keeps the original row. */
        public data class Failed(
            val chat: String,
            val message: String,
            val isUncertain: Boolean,
            val operation: String,
        ) : Internal

        /** Authoritative reservation and capability snapshot; not the screen's optimistic running flag. */
        public data class Activity(val busy: Set<String>, val steerable: Set<String>) : Internal

        /** Reported before releasing the run reservation, so failure pauses the FIFO before another send. */
        public data class Settled(
            val chat: String,
            val request: String,
            val outcome: RunOutcome,
            val operation: String,
            val isSubmitted: Boolean = true,
        ) : Internal
    }
}

/** The profile owner hands execution effects to its own scope; new queue edits cannot cancel native delivery. */
public sealed interface StudioQueueEffect : MachineEffect {
    /** Commit the snapshot before acknowledging input or invoking native work. */
    public data class Save(val operation: String, val data: StudioMessageQueues, val next: StudioQueueEffect? = Wake) :
        StudioQueueEffect

    /** Reconsider all waiting chats after commit or reservation changes. */
    public data object Wake : StudioQueueEffect

    /** Start a new turn with the queued message's stable request id. */
    public data class Dispatch(val chat: String, val message: StudioQueuedMessage) : StudioQueueEffect

    /** Steer the captured active native turn without starting a new one. */
    public data class Steer(val chat: String, val message: StudioQueuedMessage) : StudioQueueEffect
}

/** Completion of one journal mutation, including refusal when persistence failed. */
public data class StudioQueueOutput(val operation: String, val isStored: Boolean) : MachineOutput

/** Address of the profile-owned human message FIFO. */
public object StudioQueueMachineKey : MachineKey<
    StudioQueueState,
    StudioQueueIntent,
    StudioQueueIntent.Public,
    StudioQueueEffect,
    StudioQueueOutput,
> {
    override val name: String = "studio-message-queue"
}

/**
 * Profile FIFO. Every mutation stays in the same state so pending native effects keep their lifetime.
 *
 * | Intent | Guard | Data / effect |
 * |---|---|---|
 * | Enqueue | valid unique input | append Pending, Save |
 * | Drain | idle, unpaused, first Pending, no active request | Sending, Save then Dispatch |
 * | Steer | busy, supported, selected Pending, no delivery in flight | Steering, Save then Steer |
 * | Accepted | matching Sending or Steering | remove only that row, Save |
 * | Failed | matching row | retain input; uncertain or failed new turn pauses, Save |
 * | Pause / Resume | no write pending | change automatic continuation, Save |
 * | Settled | current request or external turn | clear execution owner; failure pauses, Save |
 * | Stored / StoreFailed | exact operation | publish durable state / rollback, output receipt |
 * | Activity | no write pending | authoritative busy/capability snapshot, Wake |
 */
public fun studioQueueMachineSpec(
    initial: StudioMessageQueues,
): MachineSpec<StudioQueueState, StudioQueueIntent, StudioQueueEffect, StudioQueueOutput> = machineSpec(
    StudioQueueMachineKey,
    StudioQueueState(initial),
) {
    state<StudioQueueState> {
        queueEnqueue()
        queueRemove()
        queueDrain()
        queueSteer()
        queuePause()
        queueResume()
        queueAccepted()
        queueFailed()
        queueSettled()
        queueActivity()
        queueAvailability()
        queueStored()
        queueStoreFailed()
    }
    onEffectFailure { effect, _ ->
        (effect as? StudioQueueEffect.Save)?.let { StudioQueueIntent.Internal.StoreFailed(it.operation) }
    }
}

private typealias QueueTransitions = StateBuilder<
    StudioQueueState,
    StudioQueueState,
    StudioQueueIntent,
    StudioQueueEffect,
    StudioQueueOutput,
>

private fun QueueTransitions.queueEnqueue() {
    on<StudioQueueIntent.Public.Enqueue>(guard = {
        state.isEnabled && state.saving == null && intent.message.id.isNotBlank() &&
            (intent.message.text.isNotBlank() || intent.message.attachments.isNotEmpty()) &&
            state.data.chat(intent.chat).messages.none { it.id == intent.message.id }
    }) {
        val update: TransitionScope<StudioQueueState, StudioQueueIntent.Public.Enqueue>.() -> StudioMessageQueues = {
            val old = state.data.chat(intent.chat)
            state.data.withChat(intent.chat, old.copy(messages = old.messages + intent.message))
        }
        stay { state.copy(data = update(), saving = intent.operation) }
        effect { StudioQueueEffect.Save(intent.operation, update()) }
    }
}

private fun QueueTransitions.queueRemove() {
    on<StudioQueueIntent.Public.Remove>(guard = {
        state.saving == null && state.data.chat(intent.chat).messages.any {
            it.id == intent.message && it.delivery in setOf(
                StudioQueuedDelivery.Pending,
                StudioQueuedDelivery.Uncertain,
            )
        }
    }) {
        val update: TransitionScope<StudioQueueState, StudioQueueIntent.Public.Remove>.() -> StudioMessageQueues = {
            val old = state.data.chat(intent.chat)
            state.data.withChat(
                intent.chat,
                old.copy(
                    messages = old.messages.filterNot { it.id == intent.message },
                    activeRequest = old.activeRequest.takeUnless { it == intent.message },
                ),
            )
        }
        stay { state.copy(data = update(), saving = intent.operation) }
        effect { StudioQueueEffect.Save(intent.operation, update()) }
    }
}

private fun QueueTransitions.queueDrain() {
    on<StudioQueueIntent.Public.Drain>(guard = {
        val queue = state.data.chat(intent.chat)
        state.isEnabled && state.saving == null && intent.chat !in state.busy && !queue.isPaused &&
            queue.activeRequest == null &&
            queue.messages.firstOrNull()?.delivery == StudioQueuedDelivery.Pending
    }) {
        val message: TransitionScope<StudioQueueState, StudioQueueIntent.Public.Drain>.() -> StudioQueuedMessage = {
            state.data.chat(intent.chat).messages.first()
        }
        val update: TransitionScope<StudioQueueState, StudioQueueIntent.Public.Drain>.() -> StudioMessageQueues = {
            val old = state.data.chat(intent.chat)
            state.data.withChat(
                intent.chat,
                old.copy(
                    activeRequest = message().id,
                    messages = old.messages.map {
                        if (it.id == message().id) it.copy(delivery = StudioQueuedDelivery.Sending) else it
                    },
                ),
            )
        }
        stay { state.copy(data = update(), saving = intent.operation) }
        effect {
            StudioQueueEffect.Save(
                intent.operation,
                update(),
                StudioQueueEffect.Dispatch(intent.chat, message()),
            )
        }
    }
}

private fun QueueTransitions.queueSteer() {
    on<StudioQueueIntent.Public.Steer>(guard = {
        val queue = state.data.chat(intent.chat)
        state.isEnabled && state.saving == null && intent.chat in state.busy && intent.chat in state.steerable &&
            queue.messages.none { it.delivery != StudioQueuedDelivery.Pending } &&
            queue.messages.any { it.id == intent.message && it.delivery == StudioQueuedDelivery.Pending }
    }) {
        val message: TransitionScope<StudioQueueState, StudioQueueIntent.Public.Steer>.() -> StudioQueuedMessage = {
            state.data.chat(intent.chat).messages.first { it.id == intent.message }
        }
        val update: TransitionScope<StudioQueueState, StudioQueueIntent.Public.Steer>.() -> StudioMessageQueues = {
            val old = state.data.chat(intent.chat)
            state.data.withChat(
                intent.chat,
                old.copy(
                    messages = old.messages.map {
                        if (it.id == intent.message) it.copy(delivery = StudioQueuedDelivery.Steering) else it
                    },
                ),
            )
        }
        stay { state.copy(data = update(), saving = intent.operation) }
        effect { StudioQueueEffect.Save(intent.operation, update(), StudioQueueEffect.Steer(intent.chat, message())) }
    }
}

private fun QueueTransitions.queuePause() {
    on<StudioQueueIntent.Public.Pause>(guard = { state.saving == null }) {
        val data: TransitionScope<StudioQueueState, StudioQueueIntent.Public.Pause>.() -> StudioMessageQueues = {
            state.data.withChat(intent.chat, state.data.chat(intent.chat).copy(isPaused = true))
        }
        stay { state.copy(data = data(), saving = intent.operation) }
        effect { StudioQueueEffect.Save(intent.operation, data()) }
    }
}

private fun QueueTransitions.queueResume() {
    on<StudioQueueIntent.Public.Resume>(guard = { state.saving == null }) {
        val data: TransitionScope<StudioQueueState, StudioQueueIntent.Public.Resume>.() -> StudioMessageQueues = {
            state.data.withChat(intent.chat, state.data.chat(intent.chat).copy(isPaused = false))
        }
        stay { state.copy(data = data(), saving = intent.operation) }
        effect { StudioQueueEffect.Save(intent.operation, data()) }
    }
}

private fun QueueTransitions.queueAccepted() {
    on<StudioQueueIntent.Internal.Accepted>(guard = {
        state.saving == null && state.data.chat(intent.chat).messages.any {
            it.id == intent.message && it.delivery in setOf(StudioQueuedDelivery.Sending, StudioQueuedDelivery.Steering)
        }
    }) {
        val data: TransitionScope<StudioQueueState, StudioQueueIntent.Internal.Accepted>.() -> StudioMessageQueues = {
            val old = state.data.chat(intent.chat)
            state.data.withChat(intent.chat, old.copy(messages = old.messages.filterNot { it.id == intent.message }))
        }
        stay { state.copy(data = data(), saving = intent.operation) }
        effect { StudioQueueEffect.Save(intent.operation, data()) }
    }
}

private fun QueueTransitions.queueFailed() {
    on<StudioQueueIntent.Internal.Failed>(guard = { state.saving == null }) {
        val data: TransitionScope<StudioQueueState, StudioQueueIntent.Internal.Failed>.() -> StudioMessageQueues = {
            val old = state.data.chat(intent.chat)
            val item = old.messages.firstOrNull { it.id == intent.message }
            state.data.withChat(
                intent.chat,
                old.copy(
                    isPaused = old.isPaused || intent.isUncertain || item?.delivery == StudioQueuedDelivery.Sending,
                    activeRequest = old.activeRequest.takeUnless { it == intent.message },
                    messages = old.messages.map {
                        if (it.id == intent.message) {
                            it.copy(
                                delivery = if (intent.isUncertain) {
                                    StudioQueuedDelivery.Uncertain
                                } else {
                                    StudioQueuedDelivery.Pending
                                },
                            )
                        } else {
                            it
                        }
                    },
                ),
            )
        }
        stay { state.copy(data = data(), saving = intent.operation) }
        effect { StudioQueueEffect.Save(intent.operation, data()) }
    }
}

private fun QueueTransitions.queueSettled() {
    on<StudioQueueIntent.Internal.Settled>(guard = {
        state.saving == null && state.data.chat(intent.chat).activeRequest.let { it == null || it == intent.request }
    }) {
        val data: TransitionScope<StudioQueueState, StudioQueueIntent.Internal.Settled>.() -> StudioMessageQueues = {
            val old = state.data.chat(intent.chat)
            val hasUnsent = old.messages.any { it.id == intent.request && it.delivery == StudioQueuedDelivery.Sending }
            state.data.withChat(
                intent.chat,
                old.copy(
                    activeRequest = null,
                    isPaused = old.isPaused || intent.outcome != RunOutcome.Completed || hasUnsent,
                    messages = old.messages.map { it.afterSettlement(intent.request, intent.isSubmitted) },
                ),
            )
        }
        stay { state.copy(data = data(), saving = intent.operation) }
        effect { StudioQueueEffect.Save(intent.operation, data()) }
    }
}

private fun QueueTransitions.queueActivity() {
    on<StudioQueueIntent.Internal.Activity>(guard = { state.saving == null }) {
        stay { state.copy(busy = intent.busy, steerable = intent.steerable) }
        effect { StudioQueueEffect.Wake }
    }
}

private fun QueueTransitions.queueAvailability() {
    on<StudioQueueIntent.Internal.Availability>(guard = { state.saving == null }) {
        stay { state.copy(isEnabled = intent.isEnabled) }
        effect { StudioQueueEffect.Wake }
    }
}

private fun QueueTransitions.queueStored() {
    on<StudioQueueIntent.Internal.Stored>(guard = { state.saving == intent.operation }) {
        stay { state.copy(durable = state.data, saving = null) }
        output { StudioQueueOutput(intent.operation, true) }
        effect { intent.next }
    }
}

private fun QueueTransitions.queueStoreFailed() {
    on<StudioQueueIntent.Internal.StoreFailed>(guard = { state.saving == intent.operation }) {
        stay {
            val safe = state.durable.copy(
                chats = state.durable.chats.mapValues { (_, queue) ->
                    queue.copy(
                        isPaused = true,
                        messages = queue.messages.map { it.quarantined() },
                    )
                },
            )
            state.copy(data = safe, durable = safe, saving = null)
        }
        output { StudioQueueOutput(intent.operation, false) }
    }
}

private fun StudioQueuedMessage.afterSettlement(request: String, isSubmitted: Boolean): StudioQueuedMessage {
    if (id != request || delivery != StudioQueuedDelivery.Sending) return this
    return copy(delivery = if (isSubmitted) StudioQueuedDelivery.Uncertain else StudioQueuedDelivery.Pending)
}

private fun StudioQueuedMessage.quarantined(): StudioQueuedMessage = when (delivery) {
    StudioQueuedDelivery.Sending, StudioQueuedDelivery.Steering -> copy(delivery = StudioQueuedDelivery.Uncertain)
    StudioQueuedDelivery.Pending, StudioQueuedDelivery.Uncertain -> this
}
