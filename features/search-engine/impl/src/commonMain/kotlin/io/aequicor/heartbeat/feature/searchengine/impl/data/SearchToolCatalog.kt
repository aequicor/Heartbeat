package io.aequicor.heartbeat.feature.searchengine.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Search remains on adapter transports; this contribution supplies its shared hosted policy and trust gate. */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class SearchToolCatalog(private val toggles: FeatureToggles) : AgentToolContribution {
    override val group = "search"
    override val title = "Поиск"
    override val isDetachedSupported = true
    override val isAdapterOperated = true
    override val catalog = SearchSpecifications.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(SearchEngineTools)) SearchSpecifications else emptyList()

    override fun approval(spec: AgentToolSpec, arguments: JsonObject): AgentToolApproval =
        AgentToolApproval(spec.name, spec.description, arguments.toString())

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        AgentToolResult("Search must use its adapter transport", isError = true)
}

private val SearchSpecifications = listOf(
    searchSpec("web_search", "Find web sources and citation URLs", "query"),
    searchSpec("web_fetch", "Read the text content of a URL", "url"),
)

private fun searchSpec(name: String, description: String, field: String): AgentToolSpec = AgentToolSpec(
    name,
    description,
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put(field, buildJsonObject { put("type", "string") }) })
        put("required", JsonArray(listOf(JsonPrimitive(field))))
    },
    action = AgentToolAction.Read,
)
