package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol

import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatComposer
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatModel
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Model, reasoning effort (`null`: the model's default) and approvals: `ask`, `auto_edits` or `auto_approve`. */
@Serializable
internal data class WireSettings(val modelId: String, val effort: String? = null, val approval: String = ASK) {
    /** The studio's settings; `null` for an unknown approval. */
    fun toSettings(): StudioChatSettings? = approvalOf(approval)?.let { StudioChatSettings(modelId, effort, it) }

    companion object {
        const val ASK = "ask"
        const val AUTO_EDITS = "auto_edits"
        const val AUTO_APPROVE = "auto_approve"
    }
}

/** What the composer offers: models and the settings of a new chat (`null` without a model). */
@Serializable
internal data class WireComposer(val models: List<WireModel>, val newChat: WireSettings? = null)

/** A model chats can run on; see `StudioChatModel`. */
@Serializable
internal data class WireModel(
    val id: String,
    val name: String,
    val shortName: String,
    val efforts: List<String> = emptyList(),
    val newChatEffort: String? = null,
    @SerialName("trust") val isTrustSupported: Boolean = false,
    @SerialName("projects") val isProjectSupported: Boolean = false,
    val connection: String? = null,
)

/** `chat.configure`: one change of an existing chat — `model`, `effort` (`value` null: default) or `approval`. */
@Serializable
internal data class ChatConfigureParams(val chatId: String, val kind: String, val value: String? = null) {
    /** The studio's change; `null` when the parameters do not name a valid one. */
    fun toChange(): StudioSettingChange? = when (kind) {
        "model" -> value?.let(StudioSettingChange::Model)
        "effort" -> StudioSettingChange.Effort(value)
        "approval" -> value?.let(::approvalOf)?.let(StudioSettingChange::Approval)
        else -> null
    }
}

/** `composer.effort`: the profile's reasoning effort of new chats on [modelId]; `null` restores the default. */
@Serializable
internal data class ComposerEffortParams(val modelId: String, val effort: String? = null)

/** `chat.edit`: `rename` with [title], or `pin`, `unread`, `archive` set to [isSet] (`value` on the wire). */
@Serializable
internal data class ChatEditParams(
    val chatId: String,
    val kind: String,
    val title: String? = null,
    @SerialName("value") val isSet: Boolean? = null,
) {
    /** The studio's edit; `null` when the parameters do not name a valid one. */
    fun toEdit(): SessionEdit? = when (kind) {
        "rename" -> title?.takeIf { it.isNotBlank() && it.length <= ChatWireLimits.MAX_TITLE_CHARS }
            ?.let(SessionEdit::Rename)

        "pin" -> isSet?.let(SessionEdit::SetPinned)

        "unread" -> isSet?.let(SessionEdit::SetUnread)

        "archive" -> isSet?.let(SessionEdit::SetArchived)

        else -> null
    }
}

/** The composer as sent; model names are capped like titles. */
internal fun StudioChatComposer.toWire(): WireComposer = WireComposer(
    models = models.map { it.toWire() },
    newChat = newChat?.toWire(),
)

/** A chat's settings as sent. */
internal fun StudioChatSettings.toWire(): WireSettings = WireSettings(
    modelId = modelId,
    effort = effort,
    approval = when (approval) {
        ApprovalMode.Ask -> WireSettings.ASK
        ApprovalMode.AutoEdits -> WireSettings.AUTO_EDITS
        ApprovalMode.AutoApprove -> WireSettings.AUTO_APPROVE
    },
)

private fun StudioChatModel.toWire() = WireModel(
    id = id,
    name = name.take(ChatWireLimits.MAX_TITLE_CHARS),
    shortName = shortName.take(ChatWireLimits.MAX_TITLE_CHARS),
    efforts = efforts,
    newChatEffort = newChatEffort,
    isTrustSupported = isTrustSupported,
    isProjectSupported = isProjectSupported,
    connection = connection,
)

private fun approvalOf(wire: String): ApprovalMode? = when (wire) {
    WireSettings.ASK -> ApprovalMode.Ask
    WireSettings.AUTO_EDITS -> ApprovalMode.AutoEdits
    WireSettings.AUTO_APPROVE -> ApprovalMode.AutoApprove
    else -> null
}
