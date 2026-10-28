package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding.ToolDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding.ToolParameterDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding.ToolParameterType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Metadata-only GitHub tools use their own exact-payload gate; the generic command policy permits only bounded
 * own-project network operations (push, reads, this repository's writes, declared dependency resolution).
 * A single mutation slot prevents this owner from racing its own preflight checks; external GitHub writers can
 * still race because these endpoints have no atomic expected-revision contract.
 */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class GitHubPullTools(
    private val configuration: GitHubPullConfigurationSource,
    private val toggles: FeatureToggles,
    transport: GitHubPullTransport,
) : AgentToolContribution {
    private val log = Log.tag("GitHubPullTools")
    private val api = GitHubPullApi(transport)
    private val mutations = Mutex()
    override val group = "github"
    override val title = "GitHub PR"
    override val catalog = PullSpecs.map { ToolCatalogEntry(it.name, it.action) }

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (workspace != null && toggles.get(GitHubPullToolsEnabled) && configuration.isAvailable()) {
            PullSpecs
        } else {
            emptyList()
        }

    override suspend fun instructions(workspace: WorkspaceRef?): String = if (specifications(workspace).isEmpty()) {
        ""
    } else {
        "GitHub PR tools target only the public repository configured by the desktop host. " +
            "Send full title/body inline. Read first and pass expected_revision for update or ready. " +
            "Never retry an unknown write outcome blindly: read GitHub to reconcile. " +
            "Ready does not certify a commit or perform review/merge. REST is used except the fixed ready " +
            "GraphQL mutation. Credentials are host-owned; never request them in chat or discover them with tools."
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = snapshot(context, spec.name, arguments).approval

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        try {
            mutations.withLock {
                val snapshot = snapshot(context, name, arguments)
                if (!snapshot.operation.isRead && context.authorization != snapshot.approval) {
                    AgentToolResult("GitHub authorization is missing, changed or already consumed", isError = true)
                } else {
                    currentCoroutineContext().ensureActive()
                    val result = api.perform(snapshot.configuration, snapshot.operation)
                    AgentToolResult(result.toString())
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "GitHub PR operation failed" }
            AgentToolResult(
                "GitHub operation failed. A write may have reached GitHub; read the PR before proposing any retry. " +
                    "Check host configuration, public repository, permissions and expected_revision.",
                isError = true,
            )
        }

    private suspend fun snapshot(context: AgentToolContext, name: String, arguments: JsonObject): GitHubPullSnapshot {
        check(toggles.get(GitHubPullToolsEnabled)) { "GitHub tools disabled" }
        val configured = configuration.current(
            requireNotNull(context.workspace),
        ) ?: error("GitHub host configuration is missing")
        return GitHubPullSnapshot(configured, GitHubPullOperation.parse(name, arguments), context)
    }
}

/** Desktop-only registration; the tool is disabled until the operator explicitly enables it. */
@BindingContainer
@ContributesTo(AppScope::class)
public object GitHubPullToggleBindings {
    /** Explicit opt-in to the scoped PR metadata capability. */
    @Provides
    @IntoSet
    public fun githubPullTools(): FeatureToggle<*> = GitHubPullToolsEnabled
}

private val PullSpecs = listOf(
    pullSpec("github_pr_read", "Read a PR and its revision in the host-configured public repository.", "number"),
    pullSpec(
        "github_pr_create",
        "Create a draft PR in the host-configured public repository. Branches must already exist; full text inline.",
        "title",
        "body",
        "head",
        "base",
    ),
    pullSpec(
        "github_pr_update",
        "Replace a PR title and body after reading expected_revision. Full text inline; may trigger automation.",
        "number",
        "expected_revision",
        "title",
        "body",
    ),
    pullSpec(
        "github_pr_ready",
        "Mark a draft ready after reading expected_revision. May trigger automation; does not certify or merge code.",
        "number",
        "expected_revision",
    ),
)

private fun pullSpec(name: String, description: String, vararg fields: String): AgentToolSpec = ToolDescriptor(
    name,
    description,
    fields.map {
        ToolParameterDescriptor(
            it,
            if (it == "expected_revision") "revision from github_pr_read" else it,
            if (it == "number") ToolParameterType.Integer else ToolParameterType.String,
        )
    },
    emptyList(),
).specification(if (name == "github_pr_read") AgentToolAction.Read else AgentToolAction.Command)
