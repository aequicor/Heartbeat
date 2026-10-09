package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessContent
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessScriptInstructionAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Static declarations survive frozen native sessions; each read resolves current host-authorized activation. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessContentTools(
    private val toggles: FeatureToggles,
    private val access: Lazy<HarnessActiveAccess>,
    private val scripts: Lazy<HarnessScriptInstructionAccess> = lazy { HarnessScriptInstructionAccess { "" } },
) : AgentToolContribution {
    private val log = Log.tag("HarnessTools")
    override val group: String = "harness"
    override val title: String = "Харнессы"
    override val isDetachedSupported: Boolean = true
    override val hasIndependentInstructions: Boolean = true
    override val catalog get() = HARNESS_CONTENT_SPECS.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(HarnessEnabled)) HARNESS_CONTENT_SPECS else emptyList()

    override suspend fun instructions(scope: AgentToolScope): String {
        if (!toggles.get(HarnessEnabled)) return ""
        val guide = harnessContentGuide(scope.declared)
        if (!scope.isRefreshedPerTurn) return guide.joinToString("\n")
        val active = access.value.active(scope.workspace, scope.session)
        if (!toggles.get(HarnessEnabled)) return ""
        val declared = scope.declared
        val canReadFullContext = declared == null || HarnessTools.CONTEXT in declared
        val dynamic = scripts.value.instructions(scope)
        if (!toggles.get(HarnessEnabled)) return ""
        return HarnessContent(active, canReadFullContext).block(guide, dynamic).text
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(HarnessEnabled)) return unavailable()
        val content = HarnessContent(access.value.active(context.workspace, context.session))
        if (!toggles.get(HarnessEnabled)) return unavailable()
        return try {
            val text = when (name) {
                HarnessTools.CONTEXT -> {
                    require(arguments.isEmpty()) { "Context takes no arguments" }
                    content.fullText().ifBlank { "No active harnesses" }
                }

                HarnessTools.SKILL_LOAD -> {
                    require(arguments.keys == setOf("name")) { "Only name is accepted" }
                    content.skill(arguments.string("name"))
                }

                HarnessTools.PROMPT_GET -> {
                    require(arguments.keys.all { it == "name" || it == "args" }) { "Unexpected arguments" }
                    val values = arguments["args"]?.let {
                        require(it is JsonObject) { "args must be an object" }
                        it.mapValues { (_, value) ->
                            require(value is JsonPrimitive && value.isString) { "Arguments must be strings" }
                            value.content
                        }
                    }.orEmpty()
                    content.render(arguments.string("name"), values)
                }

                else -> return unavailable()
            }
            AgentToolResult(text)
        } catch (error: IllegalArgumentException) {
            log.w(IllegalArgumentException(error::class.simpleName)) { "Harness content request rejected" }
            AgentToolResult(
                "Invalid content request: use an enabled, unambiguous name and the template's exact string arguments",
                isError = true,
            )
        }
    }

    private fun unavailable() = AgentToolResult("Harness content is unavailable", isError = true)
}

private fun JsonObject.string(key: String): String {
    val value = this[key]
    require(value is JsonPrimitive && value.isString && value.content.isNotBlank()) { "Missing string argument" }
    return value.content
}

internal fun harnessContentGuide(declared: Set<String>?): List<String> = buildList {
    add("Харнессы содержат инструкции, скиллы и шаблоны. Используй их для подходящих задач; не сохраняй секреты.")
    if (declared == null || HarnessTools.SKILL_LOAD in declared) {
        add("Перед задачей загрузи подходящий скилл через harness_skill_load по имени harness/name.")
    }
    if (declared == null || HarnessTools.PROMPT_GET in declared) {
        add("Шаблон шага получи через harness_prompt_get с именем harness/name и строковыми args.")
    }
    if (declared == null || HarnessTools.CONTEXT in declared) {
        add("Актуальные инструкции и индексы доступны через harness_context.")
    }
}
