package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.hasHiddenCharacters
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.isHarnessInputSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Kinds of agent-authored items; code kinds compile before shared profile authorization. */
internal enum class ItemKind(val key: String, val isCode: Boolean) {
    Skill("skill", false),
    Instruction("instruction", false),
    Template("template", false),
    Script("script", true),
    Workflow("workflow", true),
}

/** Validated item content without identity; the host assigns ids and keeps existing enable flags. */
internal data class ItemDraft(
    val kind: ItemKind,
    val name: ItemName,
    val description: String,
    val body: String,
    val input: JsonObject = JsonObject(emptyMap()),
) {
    /** Template arguments are exactly the placeholders of its body. */
    val arguments: Set<String> get() = TEMPLATE_ARGUMENT.findAll(body).map { it.groupValues[1] }.sorted().toSet()

    fun toItem(id: ItemId, isEnabled: Boolean): HarnessItem = when (kind) {
        ItemKind.Skill -> HarnessItem.Skill(id, name, description, body, isEnabled)
        ItemKind.Instruction -> HarnessItem.Instruction(id, name, body, isEnabled)
        ItemKind.Template -> HarnessItem.Template(id, name, description, body, arguments, isEnabled)
        ItemKind.Script -> HarnessItem.Script(id, name, description, body, isEnabled)
        ItemKind.Workflow -> HarnessItem.Workflow(id, name, description, body, input, isEnabled)
    }

    override fun toString(): String = "ItemDraft(kind=$kind, name=$name, ***)"
}

/** One parsed authoring call; harness references are slugs resolved against the committed library. */
internal sealed interface AuthoringCommand {
    data class Create(val name: HarnessName, val title: String, val description: String, val isAttached: Boolean) :
        AuthoringCommand {
        override fun toString(): String = "Create(name=$name, ***)"
    }

    data class Update(val harness: HarnessName, val title: String?, val description: String?) : AuthoringCommand {
        override fun toString(): String = "Update(harness=$harness, ***)"
    }

    data class Delete(val harness: HarnessName) : AuthoringCommand

    data class Attach(val harness: HarnessName, val isAttached: Boolean) : AuthoringCommand

    data class PutItem(val harness: HarnessName, val item: ItemDraft) : AuthoringCommand

    data class DeleteItem(val harness: HarnessName, val item: ItemName) : AuthoringCommand
}

/** Parsing outcome; [message] tells the model what to fix and never echoes the rejected text. */
internal sealed interface Parsed<out T> {
    data class Valid<T>(val value: T) : Parsed<T>
    data class Invalid(val message: String) : Parsed<Nothing>

    companion object {
        /**
         * A rejected argument is an answer for the model, not a fault: it travels as a value with its fixed
         * explanation, and the tool call reports the refusal once instead of every parse logging it.
         */
        fun failure(rejection: IllegalArgumentException): Invalid = Invalid(rejection.message ?: "invalid arguments")
    }
}

internal inline fun <T, R> Parsed<T>.map(transform: (T) -> R): Parsed<R> = when (this) {
    is Parsed.Valid -> Parsed.Valid(transform(value))
    is Parsed.Invalid -> this
}

/** Authoring kind of a stored item. */
internal fun HarnessItem.kind(): ItemKind = when (this) {
    is HarnessItem.Skill -> ItemKind.Skill
    is HarnessItem.Instruction -> ItemKind.Instruction
    is HarnessItem.Template -> ItemKind.Template
    is HarnessItem.Script -> ItemKind.Script
    is HarnessItem.Workflow -> ItemKind.Workflow
}

internal fun parseCreate(arguments: JsonObject): Parsed<AuthoringCommand.Create> = parsing {
    only(arguments, "name", "title", "description", "scope", "attach")
    AuthoringCommand.Create(
        harnessName(arguments.text("name")),
        line(arguments.text("title"), "title", TITLE_CHARS, isRequired = true),
        line(arguments.text("description"), "description", DESCRIPTION_CHARS, isRequired = false),
        arguments.flag("attach") ?: true,
    ).also { attachedOnly(arguments) }
}

internal fun parseUpdate(arguments: JsonObject): Parsed<AuthoringCommand.Update> = parsing {
    only(arguments, "harness", "title", "description", "scope")
    val command = AuthoringCommand.Update(
        harnessName(arguments.text("harness")),
        arguments.text("title")?.let { line(it, "title", TITLE_CHARS, isRequired = true) },
        arguments["description"]?.let { line(arguments.text("description"), "description", DESCRIPTION_CHARS, false) },
    )
    attachedOnly(arguments)
    argument(command.title != null || command.description != null) { "nothing to change: pass title or description" }
    command
}

internal fun parseHarnessRef(arguments: JsonObject): Parsed<HarnessName> = parsing {
    only(arguments, "harness")
    harnessName(arguments.text("harness"))
}

internal fun parseItemDelete(arguments: JsonObject): Parsed<AuthoringCommand.DeleteItem> = parsing {
    only(arguments, "harness", "name")
    AuthoringCommand.DeleteItem(harnessName(arguments.text("harness")), itemName(arguments.text("name")))
}

/** Reads harness_item_put against its schema and size limits; content authorization belongs to the shared gate. */
internal fun parseItemPut(arguments: JsonObject): Parsed<AuthoringCommand.PutItem> = parsing {
    only(arguments, "harness", "kind", "name", "description", "content", "input")
    val kind = ItemKind.entries.firstOrNull { it.key == arguments.text("kind") }
        ?: invalid("kind must be skill, instruction, template, script or workflow")
    val content = arguments.raw("content") ?: invalid("content is required")
    argument(content.isNotBlank()) { "content is required" }
    val limit = when (kind) {
        ItemKind.Skill -> HarnessLimits.SKILL_CHARS
        ItemKind.Instruction -> HarnessLimits.INSTRUCTION_CHARS
        ItemKind.Template -> HarnessLimits.TEMPLATE_CHARS
        ItemKind.Script, ItemKind.Workflow -> HarnessLimits.SOURCE_CHARS
    }
    argument(content.length <= limit) { "content is longer than $limit characters" }
    val description = line(
        arguments.text("description"),
        "description",
        DESCRIPTION_CHARS,
        isRequired = kind == ItemKind.Skill || kind == ItemKind.Template,
    )
    val input = arguments["input"]?.let {
        argument(kind == ItemKind.Workflow) { "input is only for workflows" }
        argument(it is JsonObject && isHarnessInputSchema(it)) {
            "input must be an object schema with string, number, boolean, enum and required"
        }
        it
    } ?: JsonObject(emptyMap())
    val draft = ItemDraft(kind, itemName(arguments.text("name")), description, content, input)
    if (kind == ItemKind.Template) {
        argument(draft.arguments.all { it.matches(ARGUMENT_NAME) }) {
            "template placeholders must be {{name}} with lowercase letters, digits and _"
        }
    }
    AuthoringCommand.PutItem(harnessName(arguments.text("harness")), draft)
}

private class InvalidArguments(message: String) : IllegalArgumentException(message)

private fun invalid(message: String): Nothing = throw InvalidArguments(message)

/** Model-input requirement: a failure is an [InvalidArguments] answer for the model, never a fault. */
@OptIn(ExperimentalContracts::class)
private inline fun argument(value: Boolean, message: () -> String) {
    contract { returns() implies value }
    if (!value) invalid(message())
}

/** Only model-input refusals become answers; any other exception is a host fault and propagates. */
private inline fun <T> parsing(block: () -> T): Parsed<T> = try {
    Parsed.Valid(block())
} catch (rejection: InvalidArguments) {
    // Model-supplied values never reach the message: every requirement above uses a fixed explanation.
    Parsed.failure(rejection)
}

private fun only(arguments: JsonObject, vararg names: String) {
    val unknown = arguments.keys - names.toSet()
    argument(unknown.isEmpty()) { "unknown arguments; accepted: ${names.joinToString()}" }
}

private fun harnessName(value: String?): HarnessName {
    argument(value != null && value.matches(HARNESS_NAME)) {
        "harness name must match [a-z][a-z0-9_]{1,19}"
    }
    return HarnessName(value)
}

private fun itemName(value: String?): ItemName {
    argument(value != null && value.matches(ITEM_NAME)) { "item name must match [a-z][a-z0-9_]{0,31}" }
    return ItemName(value)
}

/**
 * The schemas no longer offer `scope`; an off-schema value other than the only remaining `attached` is refused
 * instead of being ignored, so an agent never believes a harness became active without a connection.
 */
private fun attachedOnly(arguments: JsonObject) {
    val scope = arguments["scope"] ?: return
    argument(arguments.raw("scope")?.trim() == "attached" && scope is JsonPrimitive) {
        "only attached scope is supported; connect this harness to a chat"
    }
}

private fun line(value: String?, field: String, limit: Int, isRequired: Boolean): String {
    val text = value?.trim().orEmpty()
    argument(!isRequired || text.isNotEmpty()) { "$field is required" }
    argument(text.none { it == '\n' || it == '\r' || it == ' ' || it == ' ' }) {
        "$field must be a single line"
    }
    argument(text.length <= limit) { "$field is longer than $limit characters" }
    argument(!hasHiddenCharacters(text)) { "$field contains invisible or control characters" }
    return text
}

private fun JsonObject.raw(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.text(key: String): String? = raw(key)?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.flag(key: String): Boolean? = get(key)?.let {
    (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: invalid("$key must be a boolean")
}

internal const val TITLE_CHARS = 80
internal const val DESCRIPTION_CHARS = 300
private val HARNESS_NAME = Regex("[a-z][a-z0-9_]{1,19}")
private val ITEM_NAME = Regex("[a-z][a-z0-9_]{0,31}")
private val ARGUMENT_NAME = Regex("[a-z][a-z0-9_]{0,31}")
private val TEMPLATE_ARGUMENT = Regex("\\{\\{([^{}]+)}}")
