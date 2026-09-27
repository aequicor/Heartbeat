package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** Reference to a resource resolved by the engine/host; content is loaded separately and is never logged. */
@Serializable
public data class ResourceRef(val id: String, val mediaType: String) {
    init {
        require(id.isNotBlank() && mediaType.isNotBlank())
    }
}

/** Message/prompt parts. Reasoning is only information explicitly exposed by the engine. */
@Serializable
public sealed interface ContentPart {
    /** Plain or Markdown text. */
    @Serializable
    public data class Text(val text: String) : ContentPart {
        override fun toString(): String = "Text(length=${text.length})"
    }

    /** Engine-exposed reasoning content; never synthesized from private model state. */
    @Serializable
    public data class Reasoning(val text: String) : ContentPart {
        override fun toString(): String = "Reasoning(length=${text.length})"
    }

    /** An image reference, without eager byte loading. */
    @Serializable
    public data class Image(val resource: ResourceRef) : ContentPart

    /** A document, file or other resource. */
    @Serializable
    public data class Resource(val resource: ResourceRef) : ContentPart
}

/** Display authorship; native system prompts need not be exposed by every engine. */
@Serializable
public enum class MessageRole { User, Assistant, System }

/** Tool execution status can change while its stable item identity remains unchanged. */
@Serializable
public enum class ToolCallStatus { Pending, Running, Succeeded, Failed, Cancelled }

/** Plan progress is content, not a session lifecycle transition. */
@Serializable
public enum class PlanStepStatus { Pending, Running, Completed }

/** One engine-reported plan step. */
@Serializable
public data class PlanStep(val text: String, val status: PlanStepStatus)

/** Common immutable identity. Position is monotonic within a history generation; revisions only increase. */
@Serializable
public data class ItemInfo(val id: ItemId, val position: Long, val revision: Long, val turn: TurnId? = null) {
    init {
        require(position >= 0 && revision >= 0)
    }
}

/**
 * Normalized history items, not streaming deltas. Adapters merge chunks by id and revision.
 * Text, tool arguments and resource references are user data and must never appear in release logs.
 */
@Serializable
public sealed interface SessionItem {
    /** Stable item metadata. */
    public val info: ItemInfo

    /** A message with one or more content parts. */
    @Serializable
    public data class Message(override val info: ItemInfo, val role: MessageRole, val parts: List<ContentPart>) :
        SessionItem

    /** Structured tool invocation; arguments are serialized content, not executable facade commands. */
    @Serializable
    public data class ToolCall(
        override val info: ItemInfo,
        val call: ToolCallId,
        val name: String,
        val arguments: String,
        val status: ToolCallStatus,
    ) : SessionItem {
        override fun toString(): String = "ToolCall(info=$info, status=$status)"
    }

    /** A tool can fail while the agent continues the surrounding turn. */
    @Serializable
    public data class ToolResult(
        override val info: ItemInfo,
        val call: ToolCallId,
        val parts: List<ContentPart>,
        val failure: EngineFailure? = null,
    ) : SessionItem

    /** Current plan snapshot. */
    @Serializable
    public data class Plan(override val info: ItemInfo, val steps: List<PlanStep>) : SessionItem

    /** Engine-provided service notice, including context compaction. */
    @Serializable
    public data class Notice(override val info: ItemInfo, val text: String) : SessionItem

    /** Unsupported native content is retained as a safe placeholder, never a raw protocol dump. */
    @Serializable
    public data class UnsupportedItem(override val info: ItemInfo, val kind: String) : SessionItem
}
