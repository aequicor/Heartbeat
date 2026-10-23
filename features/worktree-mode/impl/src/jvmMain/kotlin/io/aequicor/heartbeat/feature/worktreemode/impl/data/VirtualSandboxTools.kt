package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Integration and conflict resolution operate on host-owned virtual revisions, never on shell arguments. */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class VirtualSandboxTools(private val workspaces: VirtualSandboxStore, private val journal: WorktreeJournal) :
    AgentToolContribution {
    private val log = Log.tag("VirtualSandboxTools")
    override val group: String = "virtual_sandbox"
    override val title: String = "Виртуальная песочница"
    override val catalog = specs.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (workspace != null && workspaces.find(workspace) != null) specs else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String {
        if (workspace == null || workspaces.find(workspace) == null) return ""
        val state = workspaces.state(workspace)
        return "Integrate a finished child's virtual snapshot with sandbox_integrate using " +
            "the workspace and snapshot " +
            "from its host result note. Never use git or shell to integrate. To commit the sandbox files into a " +
            "branch of the real repository use sandbox_commit with a branch that is not checked out. " +
            "To continue work that lives on another branch (a pull request) load it with sandbox_checkout, " +
            "fetch=true if it is not local yet. Merge another branch (for example the pull request base) into " +
            "the sandbox with sandbox_merge; " +
            "the next sandbox_commit records it as a merge. Read branch history with sandbox_log. To rewrite a " +
            "pushed branch (for example split a commit), load an earlier commit with sandbox_checkout at=<sha>, " +
            "bring paths back with sandbox_restore from the old tip, commit step by step and publish with " +
            "sandbox_commit force=true push=true. " +
            if (state.isResolving) {
                "The user authorized conflict resolution. Read conflict files and use sandbox_resolve_conflict for " +
                    "each resolved file; unresolved paths: ${state.conflicts.joinToString(
                        ", ",
                    )}. Then continue the task."
            } else {
                ""
            }
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        val parent = checkNotNull(context.workspace)
        val state = workspaces.state(parent)
        val child = when (spec.name) {
            "sandbox_integrate" -> Json.encodeToString(
                workspaces.state(WorkspaceRef(checkNotNull(arguments["workspace"]).jsonPrimitive.content)),
            )

            "sandbox_commit" -> "Base=${state.baseCommit}"

            "sandbox_checkout" -> "Base=${state.baseCommit}\nAll sandbox files are replaced"

            "sandbox_merge" -> "Base=${state.baseCommit}\nThe branch is merged into the sandbox files"

            "sandbox_restore" -> "Base=${state.baseCommit}\nThe paths are replaced from another commit"

            "sandbox_log" -> "Read-only branch history"

            else -> workspaces.find(parent)?.read(checkNotNull(arguments["path"]).jsonPrimitive.content).orEmpty()
        }
        val description = "Virtual revision=${state.revision}\nSnapshot=${state.snapshot}\n" +
            "Input=$arguments\nCurrent=$child"
        require(description.length <= MAX_VIRTUAL_ACTION_CHARS) { "SandboxActionTooLarge" }
        val isCommit = spec.name == "sandbox_commit"
        val isPush = isCommit && arguments["push"]?.jsonPrimitive?.content == "true"
        val isForce = isPush && arguments["force"]?.jsonPrimitive?.content == "true"
        return AgentToolApproval(
            spec.name,
            when {
                isForce -> FORCE_PUSH_TITLE
                isPush -> PUSH_TITLE
                isCommit -> COMMIT_TITLE
                else -> "Изменить виртуальное состояние"
            },
            description,
            "${state.revision}:${state.snapshot}:$arguments",
        )
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        checkNotNull(journal.authenticated(context)) { "TurnUnavailable" }
        val parent = checkNotNull(context.workspace)
        return try {
            if (name == "sandbox_log") {
                AgentToolResult(
                    workspaces.history(
                        parent,
                        checkNotNull(arguments["branch"]).jsonPrimitive.content,
                        checkNotNull(arguments["base"]).jsonPrimitive.content,
                        arguments["stat"]?.jsonPrimitive?.content == "true",
                    ),
                )
            } else {
                change(context, parent, name, arguments)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            log.w(error) { "Virtual sandbox tool refused name=$name" }
            refusal(error)
        } catch (error: IllegalArgumentException) {
            log.w(error) { "Virtual sandbox tool refused name=$name" }
            refusal(error)
        }
    }

    /** Store refusals carry only fixed reason codes, so they reach the agent; anything else stays opaque. */
    private fun refusal(error: RuntimeException): AgentToolResult {
        val code = error.message?.takeIf { REASON.matches(it) } ?: throw error
        return AgentToolResult("Refused: $code. ${hints[code].orEmpty()}".trim(), isError = true)
    }

    private suspend fun change(
        context: AgentToolContext,
        parent: WorkspaceRef,
        name: String,
        arguments: JsonObject,
    ): AgentToolResult {
        val bound = approval(context, specs.single { it.name == name }, arguments)
        check(context.authorization == bound) { "SandboxAuthorizationChanged" }
        val expectedRevision = checkNotNull(bound.binding).substringBefore(':').toLong()
        val result = when (name) {
            "sandbox_integrate" -> {
                val child = WorkspaceRef(checkNotNull(arguments["workspace"]).jsonPrimitive.content)
                val snapshot = checkNotNull(arguments["snapshot"]).jsonPrimitive.content
                val task = checkNotNull(journal.find(child))
                check(
                    snapshot in task.virtualResultSnapshots.values ||
                        (task.seal?.commit == snapshot && task.run?.outcome != null),
                ) { "SandboxResultChanged" }
                workspaces.integrate(parent, child, expectedRevision, snapshot)
            }

            "sandbox_commit" -> {
                val branch = checkNotNull(arguments["branch"]).jsonPrimitive.content
                val isPush = arguments["push"]?.jsonPrimitive?.content == "true"
                val isForce = arguments["force"]?.jsonPrimitive?.content == "true"
                val commit = workspaces.commitToBranch(
                    parent,
                    branch,
                    checkNotNull(arguments["message"]).jsonPrimitive.content,
                    isPush,
                    isForce,
                )
                val pushed = when {
                    isPush && isForce -> " and force-pushed it to origin with a lease"
                    isPush -> " and pushed it to origin"
                    else -> ""
                }
                return AgentToolResult("Committed $commit to refs/heads/$branch$pushed")
            }

            "sandbox_checkout" -> workspaces.checkoutRef(
                parent,
                checkNotNull(arguments["branch"]).jsonPrimitive.content,
                arguments["fetch"]?.jsonPrimitive?.content == "true",
                arguments["at"]?.jsonPrimitive?.content,
            )

            "sandbox_restore" -> workspaces.restorePaths(
                parent,
                checkNotNull(arguments["paths"]?.jsonArray).map { it.jsonPrimitive.content },
                checkNotNull(arguments["from"]).jsonPrimitive.content,
            )

            "sandbox_merge" -> workspaces.mergeRef(
                parent,
                checkNotNull(arguments["branch"]).jsonPrimitive.content,
                arguments["fetch"]?.jsonPrimitive?.content == "true",
            )

            "sandbox_resolve_conflict" -> workspaces.resolve(
                parent,
                checkNotNull(arguments["path"]).jsonPrimitive.content,
                arguments["content"]?.takeUnless { it.toString() == "null" }?.jsonPrimitive?.content,
                expectedRevision,
            )

            else -> error("UnknownSandboxTool")
        }
        return AgentToolResult(Json.encodeToString(result))
    }

    private companion object {
        val REASON = Regex("(Sandbox|Invalid)[A-Za-z]+|TurnUnavailable")
        val hints = mapOf(
            "SandboxBranchCheckedOut" to "The branch is checked out in another worktree: publish with push=true, " +
                "which updates origin only. A sandbox that never loaded the branch must load it with " +
                "sandbox_checkout before editing, because checkout replaces every sandbox file.",
            "SandboxBranchDiverged" to "The local branch moved independently: sandbox_merge that branch to take " +
                "its state into your files, then commit again. Never sandbox_checkout over unsaved work.",
            "SandboxBranchUnknown" to "No such local or origin branch; pass fetch=true to load it from origin.",
            "SandboxGitFailed" to "Git refused the operation, for example a push rejected because origin moved: " +
                "sandbox_merge the same branch with fetch=true to take the new origin state into your files, then " +
                "commit again; a forced push whose lease no longer matches needs a fetching checkout and a redo. " +
                "Never sandbox_checkout over unsaved work.",
            "SandboxCommitNotOnBranch" to "at must be a commit of that branch's history.",
            "SandboxCommitUnknown" to "No such commit; fetch the branch with sandbox_checkout fetch=true first.",
            "SandboxSourceChanged" to "The project checkout moved under the sandbox; wait for the user to continue.",
            "SandboxSourceDirty" to "The project checkout has uncommitted changes; wait for the user to continue.",
            "SandboxPaused" to "The sandbox is paused; wait for the user to continue.",
        )
        const val PUSH_TITLE = "Записать снапшот песочницы в ветку и отправить её в origin (push)"
        const val FORCE_PUSH_TITLE = "Переписать ветку снапшотом песочницы и отправить в origin (force push с lease)"
        const val COMMIT_TITLE = "Записать снапшот песочницы в ветку реального репозитория"
        val specs = listOf(
            AgentToolSpec(
                "sandbox_commit",
                "Commit the sandbox's current files to a local branch of the real repository, creating it or " +
                    "fast-forwarding it; no shell or git is needed. push=true also pushes the branch to origin " +
                    "(the user approves it). force=true continues the commit the sandbox loaded for the branch " +
                    "(sandbox_checkout at=<sha>) even when the branch moved past it, rewriting its history, and " +
                    "with push=true force-pushes with a lease on the last fetched origin state",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "branch":{"type":"string"},"message":{"type":"string"},"push":{"type":"boolean"},
                      "force":{"type":"boolean"}},
                      "required":["branch","message"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
            AgentToolSpec(
                "sandbox_checkout",
                "Replace ALL sandbox files with the tree of a branch of the real repository, discarding every " +
                    "change in the sandbox, for work that lives " +
                    "on a branch the sandbox was not cut from (for example a pull request branch). With fetch=true " +
                    "the branch is first fetched from origin. at=<sha> loads an earlier commit of that branch " +
                    "instead of its tip, to rewrite history from there with sandbox_commit force=true. Afterwards " +
                    "sandbox_commit to the same branch name continues that branch. No shell or git is needed",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "branch":{"type":"string"},"fetch":{"type":"boolean"},"at":{"type":"string"}},
                      "required":["branch"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
            AgentToolSpec(
                "sandbox_restore",
                "Replace files or whole directories of the sandbox with their state in another commit or branch " +
                    "(from: a full or short SHA, or a branch name); paths absent there are deleted. Use it to " +
                    "rebuild a large commit step by step on an earlier one, then sandbox_commit each step",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "paths":{"type":"array","items":{"type":"string"}},"from":{"type":"string"}},
                      "required":["paths","from"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
            AgentToolSpec(
                "sandbox_merge",
                "Three-way merge a branch of the real repository (for example the pull request base, fetch=true " +
                    "for its latest origin state) into the sandbox files. The next sandbox_commit records the merged " +
                    "tip as a second parent; merging the branch the sandbox continues catches up with it instead " +
                    "(after a rejected push). Conflicts leave the files unchanged until the user authorizes resolving",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "branch":{"type":"string"},"fetch":{"type":"boolean"}},
                      "required":["branch"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
            AgentToolSpec(
                "sandbox_log",
                "Read-only history of a branch since a base branch in the real repository: full SHA, author, " +
                    "subject and body per commit, oldest first; stat=true adds the diff stat. Fetch with " +
                    "sandbox_checkout or sandbox_merge first if origin may be newer",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "branch":{"type":"string"},"base":{"type":"string"},"stat":{"type":"boolean"}},
                      "required":["branch","base"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Read,
            ),
            AgentToolSpec(
                "sandbox_integrate",
                "Integrate a completed child's immutable virtual result",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "workspace":{"type":"string"},"snapshot":{"type":"string"}},
                      "required":["workspace","snapshot"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
            AgentToolSpec(
                "sandbox_resolve_conflict",
                "Save an explicitly authorized virtual file conflict resolution; null content deletes it",
                Json.parseToJsonElement(
                    """
                    {"type":"object","properties":{
                      "path":{"type":"string"},"content":{"type":["string","null"]}},
                      "required":["path","content"],"additionalProperties":false}
                    """.trimIndent(),
                ).jsonObject,
                AgentToolAction.Edit,
            ),
        )
    }
}

private const val MAX_VIRTUAL_ACTION_CHARS = 128 * 1024
