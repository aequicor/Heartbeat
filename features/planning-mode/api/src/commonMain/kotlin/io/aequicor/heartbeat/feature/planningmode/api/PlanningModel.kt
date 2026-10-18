package io.aequicor.heartbeat.feature.planningmode.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Studio chat a plan belongs to; opaque to this feature and safe as a directory name. */
@Serializable
@JvmInline
public value class PlanChat(public val value: String) {
    init {
        require(isValidPlanChat(value)) { "A plan chat id is 1..64 letters, digits, '-' or '_'" }
    }

    override fun toString(): String = value
}

/** Whether [value] can name a plan; ids are used as directory names in the profile. */
public fun isValidPlanChat(value: String): Boolean = value.length in 1..PlanLimits.CHAT_ID_CHARS &&
    value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_" }

/** How a plan takes part in its chat. */
@Serializable
public enum class PlanMode {
    /** The chat develops the plan; the plan is in context and the agent only reads. */
    @SerialName("planning")
    Planning,

    /** The chat implements the plan; it stays in context and the agent may change the workspace. */
    @SerialName("implementing")
    Implementing,

    /** The plan is kept but no longer attached to the chat's context. */
    @SerialName("detached")
    Detached,
}

/** Agreement on the current revision; any change of the text while planning returns it to [Draft]. */
@Serializable
public enum class PlanStatus {
    /** Being written. */
    @SerialName("draft")
    Draft,

    /** The agent asked the user to review the current revision. */
    @SerialName("proposed")
    Proposed,

    /** The user approved the current revision. */
    @SerialName("approved")
    Approved,
}

/** Who made the last change of a plan. */
@Serializable
public enum class PlanAuthor {
    /** The chat's agent through [PlanningTools.WRITE]. */
    @SerialName("agent")
    Agent,

    /** The user in the studio editor. */
    @SerialName("user")
    User,

    /** The file was changed outside the studio. */
    @SerialName("external")
    External,
}

/**
 * Plan of [chat]. [revision] grows with every change of [text] and is the base of the next write, so concurrent
 * edits by the agent and the user never overwrite each other silently. The text is user data and never logged.
 */
@Serializable
public data class Plan(
    val chat: PlanChat,
    val mode: PlanMode,
    val status: PlanStatus = PlanStatus.Draft,
    val revision: Long = 0,
    val text: String = "",
    val updatedAt: Instant,
    val updatedBy: PlanAuthor = PlanAuthor.User,
) {
    /** The first markdown heading, or null for a plan without one. */
    val title: String? get() = planTitle(text)

    override fun toString(): String =
        "Plan(chat=$chat, mode=$mode, status=$status, revision=$revision, length=${text.length}, by=$updatedBy)"
}

/** A change of the plan text. */
@Serializable
public sealed interface PlanEdit {
    /** Replaces the whole text. */
    @Serializable
    @SerialName("replace")
    public data class Replace(val text: String) : PlanEdit {
        override fun toString(): String = "Replace(length=${text.length})"
    }

    /**
     * Replaces the body of the markdown section titled [heading] (e.g. `## Steps`) up to the next heading of the same
     * or a higher level, appends the section when it is missing, or removes it when [text] is blank. [heading] is one
     * line that [isPlanSectionHeading] accepts.
     */
    @Serializable
    @SerialName("section")
    public data class Section(val heading: String, val text: String) : PlanEdit {
        init {
            require(isPlanSectionHeading(heading)) { "A section heading is one markdown heading line" }
        }

        override fun toString(): String = "Section(length=${text.length})"
    }
}

/** Bounds of plans; they keep the profile and the agent's context small. */
public object PlanLimits {
    /** Longest plan text. */
    public const val MAX_CHARS: Int = 64_000

    /** Plans kept per profile; entering planning beyond it evicts the oldest detached plan. */
    public const val MAX_PLANS: Int = 200

    /** Plan text delivered with a prompt; the rest is read with [PlanningTools.READ]. */
    public const val DELIVERY_CHARS: Int = 24_000

    /** Longest chat id. */
    public const val CHAT_ID_CHARS: Int = 64
}
