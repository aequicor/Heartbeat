package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueAction
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedOrigin

/** Existing repository handles only; looking up a conversation never opens or resumes a native session. */
internal fun interface StudioLiveConversations {
    suspend fun liveConversation(id: String): ActiveSession?
}

/** Direct user commands before queue persistence, separate from the dispatch-time access checks. */
internal interface StudioHumanQueueCommands {
    suspend fun enqueue(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        isCorrection: Boolean,
    )
    suspend fun manage(chat: String, action: StudioQueueAction, message: String)
}

/** Human FIFO admission shares the canonical chat registry with Studio and creator control. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioQueueAccess>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioHumanQueueCommands>())
@Inject
internal class StudioHumanQueue(
    private val records: StudioChatRecords,
    private val queue: StudioQueuePort,
    private val live: Lazy<StudioLiveConversations>,
) : StudioQueueAccess,
    StudioHumanQueueCommands {
    private val log = Log.tag("StudioHumanQueue")

    override suspend fun enqueue(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        isCorrection: Boolean,
    ) {
        log.d { "Capture human queue input" }
        check(
            message.origin == StudioQueuedOrigin.Human && message.directives.isEmpty() && message.configuration == null,
        ) {
            "Human input cannot forge creator delivery metadata"
        }
        validateQueuedInput(chat, environment)
        queue.enqueue(chat, message, environment, isCorrection)
    }

    override suspend fun manage(chat: String, action: StudioQueueAction, message: String) {
        log.d { "Manage human queue action=$action" }
        records.record(chat).requireHumanConversation()
        queue.manage(chat, action, message)
    }

    override suspend fun validateQueuedInput(chat: String, environment: StudioChatEnvironment?) {
        log.v { "Validate queued conversation input" }
        val record = records.record(chat).requireHumanConversation()
        check(record.organismId == null) { "Organisms own their queued input" }
    }

    override suspend fun validateCreatorInput(chat: String, origin: StudioQueuedOrigin.Creator) {
        val record = records.record(chat).requireHumanConversation()
        val creator = records.record(origin.creator.value).requireHumanConversation()
        check(record.logicalSession?.creator == origin.creator && creator.owns(origin.session)) {
            "Queued creator provenance no longer belongs to this conversation"
        }
        check(record.organismId == null) { "Organisms own their input" }
    }

    override suspend fun steeringTarget(chat: String): StudioSteeringTarget {
        log.v { "Capture exact native steering target" }
        validateQueuedInput(chat, null)
        return studioSteeringTarget(checkNotNull(live.value.liveConversation(chat)) { "No live native turn" }, chat)
    }
}
