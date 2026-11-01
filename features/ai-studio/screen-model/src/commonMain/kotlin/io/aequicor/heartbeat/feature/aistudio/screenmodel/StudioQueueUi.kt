@file:UseSerializers(ImmutableListSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Captured input awaiting native acknowledgement; contains no native paths or execution closures. */
@Serializable
@Immutable
data class QueuedMessageUi(
    val id: String,
    val text: String,
    val attachments: ImmutableList<AttachmentUi> = persistentListOf(),
    val isPending: Boolean = true,
    val isUncertain: Boolean = false,
)

/** Compact FIFO above the owning chat's composer. */
@Serializable
@Immutable
data class ChatQueueUi(
    val messages: ImmutableList<QueuedMessageUi> = persistentListOf(),
    val isPaused: Boolean = false,
)

/** Dismissing this transient feedback never mutates queued input. */
@Serializable
@Immutable
data class QueueNoticeUi(val id: String, val isSuccess: Boolean, val isUncertain: Boolean = false)

/** The same action contract is used by local and remote renderers. */
@Serializable
enum class QueueActionUi { Correct, Remove, Resume, DismissNotice }
