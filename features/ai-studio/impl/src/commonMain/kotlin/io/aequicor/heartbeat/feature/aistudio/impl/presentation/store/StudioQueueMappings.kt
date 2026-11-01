package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedDelivery
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ChatQueueUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.QueuedMessageUi
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/** Preserves known metadata while the profile queue outlives composer snapshots. */
internal fun AiStudioScreenState.queueUi(machine: AiStudioState.Ready) =
    machine.messageQueues.chats.mapValues { (_, queue) ->
        ChatQueueUi(
            queue.messages.map { message ->
                QueuedMessageUi(
                    message.id,
                    message.text,
                    message.attachments.map { ref ->
                        val id = ref.id.removePrefix("attachment:")
                        draftAttachments.values.flatten().firstOrNull { it.id == id }
                            ?: messageQueues.values.flatMap { it.messages }.flatMap { it.attachments }
                                .firstOrNull { it.id == id }
                            ?: AttachmentUi(id, "", ref.mediaType, 0)
                    }.toImmutableList(),
                    isPending = message.delivery ==
                        StudioQueuedDelivery.Pending,
                    isUncertain = message.delivery ==
                        StudioQueuedDelivery.Uncertain,
                )
            }.toImmutableList(),
            queue.isPaused,
        )
    }.toImmutableMap()
