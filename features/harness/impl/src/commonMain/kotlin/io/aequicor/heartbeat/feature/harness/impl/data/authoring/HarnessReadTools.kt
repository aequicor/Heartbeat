package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessApiReference
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.kind
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.label
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.referenceText
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads of the library, the tool catalogs and the authoring reference. Content is returned only on explicit
 * request of one item; listings show names and metadata. Nothing is logged beyond the dispatcher's own entry.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessReadTools(
    private val toggles: FeatureToggles,
    private val library: Lazy<HarnessLibraryClient>,
    private val catalogs: Lazy<HarnessToolCatalogs>,
    private val access: Lazy<HarnessActiveAccess>,
) : AgentToolContribution {
    override val group: String = "harness"
    override val title: String = "Харнессы"
    override val isDetachedSupported: Boolean = true
    override val catalog get() = HARNESS_READ_SPECS.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(HarnessEnabled)) HARNESS_READ_SPECS else emptyList()

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(HarnessEnabled)) return failure("Harnesses are turned off")
        return when (name) {
            HarnessTools.LIST -> list(context)

            HarnessTools.GET -> get(arguments)

            HarnessTools.TOOLS_CATALOG -> toolsCatalog(context)

            HarnessTools.API_REFERENCE -> {
                val topic = HarnessApiReference.entries.firstOrNull { it.key == arguments.text("topic") }
                    ?: return failure("Topic must be guide, api, events or examples")
                AgentToolResult(referenceText(topic))
            }

            else -> failure("Unknown tool")
        }
    }

    private suspend fun list(context: AgentToolContext): AgentToolResult {
        val state = library.value.ready() ?: return failure("The harness library is unavailable")
        if (state.harnesses.isEmpty()) return AgentToolResult("No harnesses yet. Create one with harness_create.")
        val active = access.value.active(context.workspace, context.session).map { it.id }.toSet()
        val attached = state.attachments[context.session].orEmpty()
        return AgentToolResult(
            state.harnesses.joinToString("\n") { entry ->
                val harness = entry.harness
                buildString {
                    append("- ").append(harness.name.value).append(" — ").append(harness.title)
                    append(" · ").append(harness.scope.label())
                    append(if (harness.isEnabled) " · on" else " · off")
                    append(" · ").append(harness.items.counts())
                    if (harness.id in attached) append(" · connected here")
                    if (harness.id in active) append(" · active here")
                }
            },
        )
    }

    private suspend fun get(arguments: JsonObject): AgentToolResult {
        val name = arguments.text("harness") ?: return failure("harness is required")
        val state = library.value.ready() ?: return failure("The harness library is unavailable")
        val entry = state.harnesses.singleOrNull { it.harness.name.value == name }
            ?: return failure("No harness named $name; see harness_list")
        val item = arguments.text("item")
        return if (item == null) {
            AgentToolResult(entry.describe())
        } else {
            val found = entry.harness.items.singleOrNull { it.name.value == item }
                ?: return failure("No item $item in $name")
            AgentToolResult(found.content())
        }
    }

    private suspend fun toolsCatalog(context: AgentToolContext): AgentToolResult {
        val catalogs = catalogs.value
        val text = buildString {
            append("Heartbeat tools (can only be turned off with hosted_off):\n")
            catalogs.hosted().forEach { group ->
                append("- ").append(group.title).append(" [").append(group.id).append("]: ")
                append(group.tools.joinToString { "${it.name} (${it.action.name.lowercase()})" }).append('\n')
            }
            val isNativeEnabling = toggles.get(HarnessNativeTools)
            append("\nNative engine tools; native enabling is ")
            append(if (isNativeEnabling) "available" else "off (flag harness.native_tools)").append(":\n")
            catalogs.engines().filter { it.nativeTools.isNotEmpty() }.forEach { engine ->
                val effective = catalogs.effective(
                    ToolPolicyScope(engine.id, context.workspace, context.session.takeIf { it.engine == engine.id }),
                )
                append("- ").append(engine.title).append(" [").append(engine.id.value).append("]: ")
                append(
                    engine.nativeTools.joinToString { tool ->
                        val state = when {
                            tool.name in effective.nativeOff -> "off"
                            tool.name in effective.nativeOn -> "on"
                            tool.isEnabledByDefault -> "on by default"
                            else -> "off by default"
                        }
                        val switch = if (tool.isGated) "on/off" else "off only"
                        "${tool.name} (${tool.action.name.lowercase()}, $state, $switch)"
                    },
                ).append('\n')
            }
        }
        return AgentToolResult(text.trimEnd())
    }

    private fun failure(message: String) = AgentToolResult(message, isError = true)
}

private fun HarnessEntry.describe(): String = buildString {
    append(harness.name.value).append(" — ").append(harness.title).append('\n')
    if (harness.description.isNotEmpty()) append(harness.description).append('\n')
    append("Scope: ").append(harness.scope.label()).append(" · ")
    append(if (harness.isEnabled) "on" else "off").append(" · revision ").append(harness.revision).append('\n')
    append("Items:\n")
    if (harness.items.isEmpty()) append("(none)\n")
    harness.items.sortedBy { it.name.value }.forEach { item ->
        append("- ").append(item.kind().key).append(' ').append(item.name.value)
        append(if (item.isEnabled) "" else " (off)")
        itemStatus[item.id]?.statusLabel()?.let { append(" · ").append(it) }
        item.description()?.takeIf(String::isNotEmpty)?.let { append(" — ").append(it) }
        append('\n')
    }
    append("Tool policy: ").append(harness.policyText())
}

private fun Harness.policyText(): String {
    val lines = tools.hostedOff.sorted().map { "Heartbeat $it off" } +
        tools.native.entries.sortedBy { it.key }.flatMap { (engine, switches) ->
            switches.entries.sortedBy { it.key }.map { (tool, switch) ->
                "$engine $tool ${if (switch == ToolSwitch.On) "on" else "off"}"
            }
        }
    return lines.ifEmpty { listOf("defaults") }.joinToString("; ")
}

private fun ItemStatus.statusLabel(): String? = when (this) {
    ItemStatus.Disabled -> null
    ItemStatus.Unsupported -> "not supported on this platform"
    is ItemStatus.Pending -> "activating"
    is ItemStatus.Active -> "active"
    is ItemStatus.Failed -> if (isDisabled) "failed and disabled" else "failed"
}

private fun HarnessItem.description(): String? = when (this) {
    is HarnessItem.Skill -> description
    is HarnessItem.Template -> description
    is HarnessItem.Script -> description
    is HarnessItem.Workflow -> description
    is HarnessItem.Instruction -> null
}

private fun HarnessItem.content(): String = when (this) {
    is HarnessItem.Skill -> body
    is HarnessItem.Instruction -> text
    is HarnessItem.Template -> body
    is HarnessItem.Script -> source
    is HarnessItem.Workflow -> source + "\n\nInput schema: " + input.toString()
}

private fun List<HarnessItem>.counts(): String = if (isEmpty()) {
    "no items"
} else {
    groupingBy { it.kind().key }.eachCount().entries.joinToString { "${it.value} ${it.key}" }
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
