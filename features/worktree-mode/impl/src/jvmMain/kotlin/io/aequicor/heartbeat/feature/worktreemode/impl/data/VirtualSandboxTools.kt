package io.aequicor.heartbeat.feature.worktreemode.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxRemoteEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Integration and conflict resolution operate on host-owned virtual revisions, never on shell arguments. Reaching
 * origin (`push`, `fetch`) needs [WorktreeSandboxRemoteEnabled].
 */
@Inject
@ContributesIntoSet(ProfileScope::class)
internal class VirtualSandboxTools(
    private val workspaces: VirtualSandboxStore,
    private val journal: WorktreeJournal,
    private val toggles: FeatureToggles,
) : AgentToolContribution {
    private val log = Log.tag("VirtualSandboxTools")
    override val group: String = "virtual_sandbox"
    override val title: String = "Виртуальная песочница"
    override val catalog = sandboxSpecs(isRemote = true).toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (workspace != null && workspaces.find(workspace) != null) {
            sandboxSpecs(toggles.get(WorktreeSandboxRemoteEnabled))
        } else {
            emptyList()
        }

    override suspend fun instructions(workspace: WorkspaceRef?): String {
        if (workspace == null || workspaces.find(workspace) == null) return ""
        val state = workspaces.state(workspace)
        val isRemote = toggles.get(WorktreeSandboxRemoteEnabled)
        return "Integrate a finished child's virtual snapshot with sandbox_integrate using " +
            "the workspace and snapshot " +
            "from its host result note. Never use git or shell to integrate. To commit the sandbox files into a " +
            "branch of the real repository use sandbox_commit with a branch that is not checked out. " +
            "To continue work that lives on another branch (a pull request) load it with sandbox_checkout" +
            (if (isRemote) ", fetch=true if it is not local yet. " else ". ") +
            "Merge another branch (for example the pull request base) into the sandbox with sandbox_merge; " +
            "the next sandbox_commit records it as a merge. Read branch history with sandbox_log. To rewrite a " +
            "branch (for example split a commit), load an earlier commit with sandbox_checkout at=<sha>, " +
            "bring paths back with sandbox_restore from the old tip, commit step by step with " +
            "sandbox_commit force=true" + (if (isRemote) " and publish with push=true. " else ". ") +
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
        return AgentToolApproval(
            spec.name,
            approvalTitle(spec.name, arguments),
            description,
            "${state.revision}:${state.snapshot}:$arguments",
        )
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        checkNotNull(journal.authenticated(context)) { "TurnUnavailable" }
        val parent = checkNotNull(context.workspace)
        val isRemoteEnabled = toggles.get(WorktreeSandboxRemoteEnabled)
        return try {
            val isRemote = arguments.flag("push") || arguments.flag("fetch")
            check(!isRemote || isRemoteEnabled) { "SandboxRemoteDisabled" }
            if (name == "sandbox_log") {
                AgentToolResult(
                    workspaces.branchHistory(
                        parent,
                        checkNotNull(arguments["branch"]).jsonPrimitive.content,
                        checkNotNull(arguments["base"]).jsonPrimitive.content,
                        arguments.flag("stat"),
                    ),
                )
            } else {
                change(context, parent, name, arguments)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Coded store errors become a refusal; any other one (for example a git process that failed to start)
            // propagates. Both are logged once, here.
            val refused = (error as? IllegalStateException ?: error as? IllegalArgumentException)
                ?.let { sandboxRefusal(it, isRemoteEnabled) }
            if (refused == null) {
                log.w(error) { "Virtual sandbox tool failed name=$name" }
                throw error
            }
            log.w(error) { "Virtual sandbox tool refused name=$name" }
            refused
        }
    }

    private suspend fun change(
        context: AgentToolContext,
        parent: WorkspaceRef,
        name: String,
        arguments: JsonObject,
    ): AgentToolResult {
        val bound = approval(context, sandboxSpecs(isRemote = true).single { it.name == name }, arguments)
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

            "sandbox_commit" -> return commit(parent, arguments, expectedRevision)

            "sandbox_checkout" -> workspaces.checkoutRef(
                parent,
                checkNotNull(arguments["branch"]).jsonPrimitive.content,
                arguments.flag("fetch"),
                arguments["at"]?.jsonPrimitive?.content,
                expectedRevision,
            )

            "sandbox_restore" -> workspaces.restorePaths(
                parent,
                checkNotNull(arguments["paths"]?.jsonArray).map { it.jsonPrimitive.content },
                checkNotNull(arguments["from"]).jsonPrimitive.content,
                expectedRevision,
            )

            "sandbox_merge" -> workspaces.mergeRef(
                parent,
                checkNotNull(arguments["branch"]).jsonPrimitive.content,
                arguments.flag("fetch"),
                expectedRevision,
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

    private suspend fun commit(parent: WorkspaceRef, arguments: JsonObject, expectedRevision: Long): AgentToolResult {
        val branch = checkNotNull(arguments["branch"]).jsonPrimitive.content
        val isPush = arguments.flag("push")
        val isForce = arguments.flag("force")
        val commit = workspaces.commitToBranch(
            parent,
            branch,
            checkNotNull(arguments["message"]).jsonPrimitive.content,
            isPush,
            isForce,
            expectedRevision,
        )
        val pushed = when {
            isPush && isForce -> " and force-pushed it to origin with a lease"
            isPush -> " and pushed it to origin"
            else -> ""
        }
        return AgentToolResult("Committed $commit to refs/heads/$branch$pushed")
    }
}

/** A forced commit is titled as a rewrite whether or not it is pushed. */
private fun approvalTitle(name: String, arguments: JsonObject): String {
    val isCommit = name == "sandbox_commit"
    val isPush = isCommit && arguments.flag("push")
    val isForce = isCommit && arguments.flag("force")
    return when {
        isForce && isPush -> FORCE_PUSH_TITLE
        isForce -> FORCE_COMMIT_TITLE
        isPush -> PUSH_TITLE
        isCommit -> COMMIT_TITLE
        else -> "Изменить виртуальное состояние"
    }
}

/**
 * Store refusals carry only fixed reason codes, so they reach the agent with a hint; null for any other error.
 * Without [isRemote] the hints never suggest pushing or fetching, which would only be refused.
 */
internal fun sandboxRefusal(error: RuntimeException, isRemote: Boolean): AgentToolResult? {
    val code = error.message?.takeIf { REASON.matches(it) } ?: return null
    return AgentToolResult("Refused: $code. ${hints(isRemote)[code].orEmpty()}".trim(), isError = true)
}

/** The sandbox tools; without [isRemote] `sandbox_commit` has no `push` and checkout/merge have no `fetch`. */
internal fun sandboxSpecs(isRemote: Boolean): List<AgentToolSpec> = branchSpecs(isRemote) + listOf(
    AgentToolSpec(
        "sandbox_log",
        "Read-only history of a branch since a base branch in the real repository: full SHA, author, " +
            "subject and body per commit, oldest first; stat=true adds the diff stat. Local branches win " +
            "over origin" +
            (if (isRemote) "; fetch with sandbox_checkout or sandbox_merge first if origin may be newer" else ""),
        schema(
            """"branch":{"type":"string"},"base":{"type":"string"},"stat":{"type":"boolean"}""",
            """"branch","base"""",
        ),
        AgentToolAction.Read,
    ),
    AgentToolSpec(
        "sandbox_integrate",
        "Integrate a completed child's immutable virtual result",
        schema(""""workspace":{"type":"string"},"snapshot":{"type":"string"}""", """"workspace","snapshot""""),
        AgentToolAction.Edit,
    ),
    AgentToolSpec(
        "sandbox_resolve_conflict",
        "Save an explicitly authorized virtual file conflict resolution; null content deletes it",
        schema(""""path":{"type":"string"},"content":{"type":["string","null"]}""", """"path","content""""),
        AgentToolAction.Edit,
    ),
)

private fun schema(properties: String, required: String) = Json.parseToJsonElement(
    """{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}""",
).jsonObject

/** Tools between the sandbox and branches of the real repository. */
private fun branchSpecs(isRemote: Boolean): List<AgentToolSpec> {
    val push = if (isRemote) ""","push":{"type":"boolean"}""" else ""
    val fetch = if (isRemote) ""","fetch":{"type":"boolean"}""" else ""
    return listOf(
        AgentToolSpec(
            "sandbox_commit",
            "Commit the sandbox's current files to a local branch of the real repository, creating it or " +
                "fast-forwarding it; no shell or git is needed. " +
                (if (isRemote) "push=true also pushes the branch to origin. " else "") +
                "force=true continues the commit the sandbox loaded for the branch " +
                "(sandbox_checkout at=<sha>) even when the branch moved past it, rewriting its history" +
                (if (isRemote) ", and with push=true force-pushes with a lease on the origin state it saw" else "") +
                ". Requires approval per the profile security mode",
            schema(
                """"branch":{"type":"string"},"message":{"type":"string"}$push,"force":{"type":"boolean"}""",
                """"branch","message"""",
            ),
            AgentToolAction.Edit,
        ),
        AgentToolSpec(
            "sandbox_checkout",
            "Replace ALL sandbox files with the tree of a branch of the real repository, discarding every " +
                "change in the sandbox, for work that lives " +
                "on a branch the sandbox was not cut from (for example a pull request branch). " +
                (if (isRemote) "With fetch=true it is fetched from origin first and that state wins. " else "") +
                "at=<sha> loads an earlier commit of that branch " +
                "instead of its tip, to rewrite history from there with sandbox_commit force=true. Afterwards " +
                "sandbox_commit to the same branch name continues that branch. No shell or git is needed",
            schema(""""branch":{"type":"string"}$fetch,"at":{"type":"string"}""", """"branch""""),
            AgentToolAction.Edit,
        ),
        AgentToolSpec(
            "sandbox_restore",
            "Replace files or whole directories of the sandbox with their state in another commit or branch " +
                "(from: a branch name, or a full or short SHA); paths absent there are deleted. Use it to " +
                "rebuild a large commit step by step on an earlier one, then sandbox_commit each step",
            schema(
                """"paths":{"type":"array","items":{"type":"string"}},"from":{"type":"string"}""",
                """"paths","from"""",
            ),
            AgentToolAction.Edit,
        ),
        AgentToolSpec(
            "sandbox_merge",
            mergeDescription(isRemote),
            schema(""""branch":{"type":"string"}$fetch""", """"branch""""),
            AgentToolAction.Edit,
        ),
    )
}

private fun mergeDescription(isRemote: Boolean): String =
    "Three-way merge a branch of the real repository (for example the pull request base" +
        (if (isRemote) ", fetch=true for its latest origin state" else "") +
        ") into the sandbox files" + (if (isRemote) "; without a fetch the local branch wins" else "") +
        ". The next sandbox_commit records the merged tip as a second parent; merging the branch the " +
        "sandbox continues catches up with it instead" + (if (isRemote) " (after a rejected push)" else "") +
        ". Conflicts leave the files unchanged until the user authorizes resolving them"

private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.content == "true"

private val REASON = Regex("(Sandbox|Invalid)[A-Za-z]+|TurnUnavailable")

/** Refusal hints; remote advice only when [isRemote], like the instructions and schemas. */
private fun hints(isRemote: Boolean): Map<String, String> = mapOf(
    "SandboxBranchCheckedOut" to if (isRemote) {
        "The branch is checked out in another worktree: publish with push=true, which updates origin only. " +
            "A sandbox that never loaded the branch must load it with sandbox_checkout before editing, because " +
            "checkout replaces every sandbox file."
    } else {
        "The branch is checked out in another worktree and cannot be moved: commit to another branch name."
    },
    "SandboxBranchDiverged" to "The local branch moved independently: sandbox_merge that branch to take " +
        "its state into your files, then commit again. Never sandbox_checkout over unsaved work.",
    "SandboxBranchMoved" to "The local branch moved after the sandbox loaded it, and a forced commit would " +
        "discard those commits: sandbox_merge that branch to keep them, then commit again. Never " +
        "sandbox_checkout over unsaved work.",
    "SandboxBranchNotLoaded" to "force=true rewrites only a branch loaded with sandbox_checkout (or committed " +
        "by this sandbox); load it first, before editing, because checkout replaces every sandbox file.",
    "SandboxBranchUnknown" to if (isRemote) {
        "No such local or origin branch; pass fetch=true to load it from origin."
    } else {
        "No such local or origin branch in the repository."
    },
    "SandboxConflicted" to "The sandbox has unresolved conflicts; resolve every conflict with " +
        "sandbox_resolve_conflict once the user authorizes it, then retry.",
    "SandboxGitFailed" to if (isRemote) {
        "Git refused the operation, for example a push rejected because origin moved: sandbox_merge the same " +
            "branch with fetch=true to take the new origin state into your files, then commit again; a forced " +
            "push whose lease no longer matches needs a fetching checkout and a redo. Never sandbox_checkout " +
            "over unsaved work."
    } else {
        "Git refused the operation; read the branch with sandbox_log and call again. Never sandbox_checkout " +
            "over unsaved work."
    },
    "SandboxCommitNotOnBranch" to "at must be a commit of that branch's history.",
    "SandboxCommitUnknown" to if (isRemote) {
        "No such commit; fetch the branch with sandbox_checkout fetch=true first."
    } else {
        "No such commit in the repository."
    },
    "SandboxRemoteDisabled" to "Pushing and fetching are turned off; work with local branches only.",
    "SandboxRevisionChanged" to "The sandbox changed since the call was approved; check its state and call again.",
    "SandboxSourceChanged" to "The project checkout moved under the sandbox; wait for the user to continue.",
    "SandboxSourceDirty" to "The project checkout has uncommitted changes; wait for the user to continue.",
    "SandboxPaused" to "The sandbox is paused; wait for the user to continue.",
)
private const val PUSH_TITLE = "Записать снапшот песочницы в ветку и отправить её в origin (push)"
private const val FORCE_PUSH_TITLE = "Переписать ветку снапшотом песочницы и отправить в origin (force push с lease)"
private const val FORCE_COMMIT_TITLE = "Переписать локальную ветку снапшотом песочницы (force)"
private const val COMMIT_TITLE = "Записать снапшот песочницы в ветку реального репозитория"
private const val MAX_VIRTUAL_ACTION_CHARS = 128 * 1024
