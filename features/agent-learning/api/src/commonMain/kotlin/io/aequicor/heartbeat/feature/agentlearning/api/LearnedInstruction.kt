package io.aequicor.heartbeat.feature.agentlearning.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable identity of a learned instruction. */
@Serializable
public data class InstructionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty InstructionId" }
    }
}

/** Where a learned instruction applies within its project. */
@Serializable
public enum class InstructionKind {
    /** Added to the initial prompt of every session. */
    @SerialName("general")
    General,

    /** Added only to sessions of one engine or one model ([LearnedInstruction.modelScope]). */
    @SerialName("model")
    Model,

    /** Only its name and description are in the prompt; the agent loads the body when the task matches. */
    @SerialName("skill")
    Skill,
}

/** An engine, or one model of it when [model] is set. */
@Serializable
public data class ModelScope(val engine: EngineId, val model: ModelId? = null)

/**
 * An instruction the agent derived during work. [project] is the project it was learned in; null means chats
 * without a project — such instructions apply only there, and a project's only to that project. [title] names a
 * skill for `load_learned_skill`; [description] says when a skill applies (optional for other kinds).
 */
@Serializable
public data class LearnedInstruction(
    val id: InstructionId,
    val kind: InstructionKind,
    val project: WorkspaceRef?,
    val title: String,
    val content: String,
    val description: String = "",
    val modelScope: ModelScope? = null,
    @SerialName("enabled") val isEnabled: Boolean = true,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = createdAtMillis,
) {
    init {
        require(title.isNotBlank()) { "Empty instruction title" }
        require(content.isNotBlank()) { "Empty instruction content" }
        require((kind == InstructionKind.Model) == (modelScope != null)) { "Model scope belongs to model instructions" }
    }

    override fun toString(): String = "LearnedInstruction(id=${id.value}, kind=$kind, enabled=$isEnabled)"
}

/** How new instructions created by the agent are accepted. */
@Serializable
public enum class LearningApproval {
    /** Every new instruction waits for the user's decision. */
    @SerialName("ask")
    Ask,

    /** The agent rates each instruction: a safe one is saved at once, any other waits for the user. */
    @SerialName("automatic")
    Automatic,

    /** Every instruction is saved without asking. */
    @SerialName("accept_all")
    AcceptAll,
    ;

    /** Whether saving an instruction the agent rated [isSafe] needs the user's decision. */
    public fun requiresDecision(isSafe: Boolean): Boolean = when (this) {
        Ask -> true
        Automatic -> !isSafe
        AcceptAll -> false
    }
}

/** Size limits of the registry; the host refuses larger instructions instead of truncating them. */
public object LearningLimits {
    /** Longest title or skill name. */
    public const val TITLE: Int = 80

    /** Longest description. */
    public const val DESCRIPTION: Int = 300

    /** Longest body of a general or model instruction. */
    public const val CONTENT: Int = 2_000

    /** Longest body of a skill. */
    public const val SKILL_CONTENT: Int = 16_000

    /** Most instructions in one profile. */
    public const val INSTRUCTIONS: Int = 300

    /** Longest allowed body for [kind]. */
    public fun content(kind: InstructionKind): Int = if (kind == InstructionKind.Skill) SKILL_CONTENT else CONTENT
}

/** Whether every text of this instruction fits [LearningLimits]. */
public fun LearnedInstruction.isWithinLimits(): Boolean = title.length <= LearningLimits.TITLE &&
    description.length <= LearningLimits.DESCRIPTION &&
    content.length <= LearningLimits.content(kind)

/** Two instructions with the same identity key describe the same lesson; the registry keeps the first. */
public fun LearnedInstruction.isSameLesson(other: LearnedInstruction): Boolean = kind == other.kind &&
    project == other.project &&
    modelScope == other.modelScope &&
    title.trim().lowercase() == other.title.trim().lowercase()
