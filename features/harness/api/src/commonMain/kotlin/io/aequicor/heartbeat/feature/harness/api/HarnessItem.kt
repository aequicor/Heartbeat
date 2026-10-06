package io.aequicor.heartbeat.feature.harness.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Agent-authored content. User edits are direct; agent code changes always require explicit approval. */
@Serializable
public sealed interface HarnessItem {
    public val id: ItemId
    public val name: ItemName
    public val isEnabled: Boolean

    /** Markdown knowledge loaded on demand by the agent. */
    @Serializable
    @SerialName("skill")
    public data class Skill(
        override val id: ItemId,
        override val name: ItemName,
        val description: String,
        val body: String,
        override val isEnabled: Boolean = true,
    ) : HarnessItem {
        init {
            require(body.length <= HarnessLimits.SKILL_CHARS)
        }
        override fun toString(): String = "HarnessItem.Skill(id=$id, name=$name, ***)"
    }

    /** Short instruction supplied to each applicable session. */
    @Serializable
    @SerialName("instruction")
    public data class Instruction(
        override val id: ItemId,
        override val name: ItemName,
        val text: String,
        override val isEnabled: Boolean = true,
    ) : HarnessItem {
        init {
            require(text.length <= HarnessLimits.INSTRUCTION_CHARS)
        }
        override fun toString(): String = "HarnessItem.Instruction(id=$id, name=$name, ***)"
    }

    /** Prompt template with explicitly named {{arguments}}; rendering does not execute code. */
    @Serializable
    @SerialName("template")
    public data class Template(
        override val id: ItemId,
        override val name: ItemName,
        val description: String,
        val body: String,
        val arguments: Set<String> = emptySet(),
        override val isEnabled: Boolean = true,
    ) : HarnessItem {
        init {
            require(body.length <= HarnessLimits.TEMPLATE_CHARS)
            require(arguments.all { it.matches(Regex("[a-z][a-z0-9_]{0,31}")) })
        }
        override fun toString(): String = "HarnessItem.Template(id=$id, name=$name, ***)"
    }

    /** Desktop Kotlin hooks, event handlers and script-owned tools. */
    @Serializable
    @SerialName("script")
    public data class Script(
        override val id: ItemId,
        override val name: ItemName,
        val description: String,
        val source: String,
        override val isEnabled: Boolean = true,
    ) : HarnessItem {
        init {
            require(source.length <= HarnessLimits.SOURCE_CHARS)
        }
        override fun toString(): String = "HarnessItem.Script(id=$id, name=$name, ***)"
    }

    /** Desktop workflow source and its v1 object/string/number/boolean input schema. */
    @Serializable
    @SerialName("workflow")
    public data class Workflow(
        override val id: ItemId,
        override val name: ItemName,
        val description: String,
        val source: String,
        val input: JsonObject = JsonObject(emptyMap()),
        override val isEnabled: Boolean = true,
    ) : HarnessItem {
        init {
            require(source.length <= HarnessLimits.SOURCE_CHARS)
            require(isHarnessInputSchema(input))
        }
        override fun toString(): String = "HarnessItem.Workflow(id=$id, name=$name, ***)"
    }
}
