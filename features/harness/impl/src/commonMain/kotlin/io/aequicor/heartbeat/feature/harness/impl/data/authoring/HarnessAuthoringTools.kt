package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessAuthor
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessDraft
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.AuthoringCommand
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.AuthoringScope
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.CodeCheck
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.ItemDraft
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.ItemKind
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.Parsed
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.key
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.kind
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.label
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.listing
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.map
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.newlyEnabled
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseCreate
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseHarnessRef
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseItemDelete
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseItemPut
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseToolsSet
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseUpdate
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.policyDiff
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.requiresDecision
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Agent side of the library: creating harnesses, their items and tool policy. Every call resolves the committed
 * library, presents the whole content and binds the approval to the harness revision; the machine applies the
 * edit only to that revision. Item texts and code are never logged. Code is compiled before the question, and a
 * failing compile is reported to the agent without asking the user or saving anything.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessAuthoringTools(
    private val toggles: FeatureToggles,
    private val library: Lazy<HarnessLibraryClient>,
    private val checks: Lazy<HarnessCodeChecks>,
    private val catalogs: Lazy<HarnessToolCatalogs>,
    private val clock: Clock,
) : AgentToolContribution {
    private val log = Log.tag("HarnessTools")
    override val group: String = "harness"
    override val title: String = "Харнессы"
    override val isDetachedSupported: Boolean = true
    override val catalog get() = HARNESS_AUTHORING_SPECS.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(HarnessEnabled)) HARNESS_AUTHORING_SPECS else emptyList()

    override suspend fun instructions(scope: AgentToolScope): String {
        if (!toggles.get(HarnessEnabled)) return ""
        val declared = scope.declared
        fun has(name: String) = declared == null || name in declared
        return buildList {
            if (has(HarnessTools.CREATE) && has(HarnessTools.ITEM_PUT)) {
                add(
                    "Харнесс — окружение под задачу или проект. Собирай его по просьбе пользователя: сначала " +
                        "harness_api_reference(topic=guide) и harness_tools_catalog, затем harness_create и " +
                        "harness_item_put.",
                )
                add(
                    "Скилл — знания по запросу; инструкция — короткое правило для каждой сессии; шаблон — промпт " +
                        "шага с {{аргументами}}; скрипт — хуки, события и свои инструменты (только Desktop); " +
                        "workflow — оркестрация помощников.",
                )
                add("Код и включение нативных инструментов пользователь подтверждает всегда. Не сохраняй секреты.")
            }
            if (has(HarnessTools.TOOLS_SET)) {
                add("Чтобы убрать инструмент, выключай его через harness_tools_set, а не Deny-хуком.")
            }
        }.joinToString("\n")
    }

    override suspend fun requiresDecision(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): Boolean {
        // Invalid requests are refused by execution deterministically, so they are never put to the user.
        val plan = plan(context, spec.name, arguments) as? Plan.Ready ?: return false
        return plan.level.requiresDecision(context.trust, plan.isAlwaysAsked)
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = when (val plan = plan(context, spec.name, arguments)) {
        is Plan.Ready -> plan.approval
        is Plan.Invalid -> AgentToolApproval(spec.name, spec.description, binding = "invalid")
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(HarnessEnabled)) return failure("harnesses are turned off")
        val plan = when (val planned = plan(context, name, arguments)) {
            is Plan.Invalid -> return failure("not saved: ${planned.message}")
            is Plan.Ready -> planned
        }
        if (context.authorization?.binding != plan.approval.binding) {
            return failure("the harness changed while awaiting approval; request it again")
        }
        val result = apply(context, plan)
        log.i { "Harness tool finished name=$name failed=${result.isError}" }
        return result
    }

    private suspend fun plan(context: AgentToolContext, name: String, arguments: JsonObject): Plan {
        val state = library.value.current ?: return Plan.Invalid("the harness library is unavailable")
        val command = when (val parsed = parse(name, arguments)) {
            is Parsed.Invalid -> return Plan.Invalid(parsed.message)
            is Parsed.Valid -> parsed.value
        }
        val plan = when (command) {
            is AuthoringCommand.Create -> planCreate(context, state, command)

            is AuthoringCommand.Update -> withHarness(state, command.harness.value) { planUpdate(context, it, command) }

            is AuthoringCommand.Delete -> withHarness(state, command.harness.value) {
                val text = "Deletes ${it.items.size} items, its connections and runs; running workflows are cancelled."
                ready(state, it, "Delete harness ${it.name.value}", text, command)
            }

            is AuthoringCommand.Attach -> withHarness(state, command.harness.value) {
                val verb = if (command.isAttached) "Connect" else "Disconnect"
                ready(state, it, "$verb harness ${it.name.value}", "", command)
            }

            is AuthoringCommand.PutItem -> withHarness(state, command.harness.value) { planItem(state, it, command) }

            is AuthoringCommand.DeleteItem -> withHarness(state, command.harness.value) { harness ->
                val item = harness.items.singleOrNull { it.name == command.item }
                    ?: return@withHarness Plan.Invalid("no item ${command.item.value} in ${harness.name.value}")
                ready(state, harness, "Delete item ${harness.name.value}/${item.name.value}", "", command)
            }

            is AuthoringCommand.SetTools -> withHarness(state, command.harness.value) { planTools(state, it, command) }
        }
        return if (plan is Plan.Ready) plan.copy(approval = plan.approval.copy(name = name)) else plan
    }

    private fun parse(name: String, arguments: JsonObject): Parsed<AuthoringCommand> = when (name) {
        HarnessTools.CREATE -> parseCreate(arguments)
        HarnessTools.UPDATE -> parseUpdate(arguments)
        HarnessTools.DELETE -> parseHarnessRef(arguments).map { AuthoringCommand.Delete(it) }
        HarnessTools.ATTACH -> parseHarnessRef(arguments).map { AuthoringCommand.Attach(it, isAttached = true) }
        HarnessTools.DETACH -> parseHarnessRef(arguments).map { AuthoringCommand.Attach(it, isAttached = false) }
        HarnessTools.ITEM_PUT -> parseItemPut(arguments)
        HarnessTools.ITEM_DELETE -> parseItemDelete(arguments)
        HarnessTools.TOOLS_SET -> parseToolsSet(arguments)
        else -> Parsed.Invalid("unknown tool")
    }

    private suspend fun planCreate(
        context: AgentToolContext,
        state: HarnessState.Ready,
        command: AuthoringCommand.Create,
    ): Plan {
        if (state.harnesses.any { it.harness.name == command.name } ||
            state.pending.values.any { it is HarnessMutation.Save && it.harness.name == command.name }
        ) {
            return Plan.Invalid("a harness named ${command.name.value} already exists; update it instead")
        }
        val scope = scopeOf(context, command.scope) ?: return Plan.Invalid(NO_PROJECT)
        val text = buildString {
            append("New harness · ").append(scope.label())
            if (command.isAttached) append(" · connected to this chat")
            append("\n\nTitle: ").append(command.title)
            if (command.description.isNotEmpty()) append("\nDescription: ").append(command.description)
        }
        return Plan.Ready(
            command,
            null,
            state.approval,
            AgentToolApproval(
                "",
                "Create harness ${command.name.value}",
                text,
                "approval=${state.approval.key};harness=new:${command.name.value}",
            ),
            scope = scope,
        )
    }

    private suspend fun planUpdate(
        context: AgentToolContext,
        harness: Harness,
        command: AuthoringCommand.Update,
    ): Plan {
        val scope = command.scope?.let { scopeOf(context, it) ?: return Plan.Invalid(NO_PROJECT) }
        val text = buildString {
            command.title?.let { append("Title: ").append(it).append('\n') }
            command.description?.let { append("Description: ").append(it.ifEmpty { "(none)" }).append('\n') }
            scope?.let { append("Scope: ${it.label()} (was ${harness.scope.label()})") }
        }.trimEnd()
        return ready(library.value.current, harness, "Update harness ${harness.name.value}", text, command, scope)
    }

    private suspend fun planItem(
        state: HarnessState.Ready,
        harness: Harness,
        command: AuthoringCommand.PutItem,
    ): Plan {
        val draft = command.item
        val existing = harness.items.singleOrNull { it.name == draft.name }
        if (existing != null && existing.kind() != draft.kind) {
            return Plan.Invalid("item ${draft.name.value} is a ${existing.kind().key}; delete it first or rename")
        }
        // A stable id lets the approval compile and the saved item share one cache entry.
        val id = existing?.id ?: ItemId("it_" + "${harness.id.value}/${draft.name.value}".digest().take(ID_CHARS))
        val check = when (val compiled = compile(harness, id, draft)) {
            is Parsed.Invalid -> return Plan.Invalid(compiled.message)
            is Parsed.Valid -> compiled.value
        }
        val title = "Save ${draft.kind.key} ${draft.name.value} in ${harness.name.value}"
        val plan = ready(state, harness, title, itemText(harness, draft, existing == null, check), command)
        val item = draft.toItem(id, existing?.isEnabled ?: true)
        return (plan as Plan.Ready).copy(isAlwaysAsked = draft.kind.isCode, item = item)
    }

    /** Code is compiled before the question; texts need no compilation and yield null. */
    private suspend fun compile(harness: Harness, id: ItemId, draft: ItemDraft): Parsed<CodeCheck.Compiled?> {
        if (!draft.kind.isCode) return Parsed.Valid(null)
        val kind = if (draft.kind == ItemKind.Script) HarnessCodeKind.Script else HarnessCodeKind.Workflow
        return when (val result = checks.value.check(harness.id, id, kind, draft.body, isAgent = true)) {
            is CodeCheck.Compiled -> Parsed.Valid(result)
            is CodeCheck.Failed -> Parsed.Invalid("compilation failed:\n" + result.diagnostics.listing())
            CodeCheck.TimedOut -> Parsed.Invalid("compilation timed out; retry later")
            CodeCheck.Unsupported -> Parsed.Invalid("Kotlin scripts and workflows run only on Desktop")
        }
    }

    private fun planTools(state: HarnessState.Ready, harness: Harness, command: AuthoringCommand.SetTools): Plan {
        val next = ToolPolicySpec(command.hostedOff, command.native)
        catalogs.value.problem(next)?.let { return Plan.Invalid(it) }
        val enabled = newlyEnabled(harness.tools, next)
        val diff = policyDiff(harness.tools, next).ifEmpty { listOf("No changes") }
        val text = buildString {
            append(diff.joinToString("\n"))
            if (enabled.isNotEmpty()) {
                append("\n\nEnabled native tools run under the trust level of each chat and its hooks.")
            }
        }
        val plan = ready(state, harness, "Tool policy of ${harness.name.value}", text, command)
        return (plan as Plan.Ready).copy(isAlwaysAsked = enabled.isNotEmpty(), tools = next)
    }

    private suspend fun apply(context: AgentToolContext, plan: Plan.Ready): AgentToolResult {
        val at = clock.now()
        val harness = plan.harness
        return when (val command = plan.command) {
            is AuthoringCommand.Create -> create(context, plan, command)

            is AuthoringCommand.Update -> update(context, checkNotNull(harness), command, plan.scope)

            is AuthoringCommand.Delete -> outcome(
                HarnessIntent.Public.Delete(request(), checkNotNull(harness).id, harness.revision),
                "Deleted harness ${harness.name.value}",
            )

            is AuthoringCommand.Attach -> {
                val id = checkNotNull(harness).id
                val intent = if (command.isAttached) {
                    HarnessIntent.Public.Attach(request(), id, context.session)
                } else {
                    HarnessIntent.Public.Detach(request(), id, context.session)
                }
                outcome(intent, if (command.isAttached) "Connected to this chat" else "Disconnected from this chat")
            }

            is AuthoringCommand.PutItem -> edit(
                context,
                checkNotNull(harness),
                HarnessChange.PutItem(checkNotNull(plan.item)),
                at,
                "Saved ${command.item.kind.key} ${harness.name.value}/${command.item.name.value}",
            )

            is AuthoringCommand.DeleteItem -> {
                val item = checkNotNull(harness).items.first { it.name == command.item }
                edit(context, harness, HarnessChange.RemoveItem(item.id), at, "Deleted item ${command.item.value}")
            }

            is AuthoringCommand.SetTools -> {
                val isOn = checkNotNull(plan.tools).native.values.any { tools -> ToolSwitch.On in tools.values }
                val note = if (isOn &&
                    !toggles.get(HarnessNativeTools)
                ) {
                    " Native On switches take effect after the harness.native_tools flag is enabled."
                } else {
                    ""
                }
                edit(context, checkNotNull(harness), HarnessChange.Tools(plan.tools), at, "Saved tool policy.$note")
            }
        }
    }

    private suspend fun create(
        context: AgentToolContext,
        plan: Plan.Ready,
        command: AuthoringCommand.Create,
    ): AgentToolResult {
        val id = HarnessId("h_" + Uuid.random().toHexString())
        val draft = HarnessDraft(command.name, command.title, command.description, checkNotNull(plan.scope))
        val intent = HarnessIntent.Public.Create(request(), id, draft, context.session, clock.now())
        val created = library.value.submit(intent).failureText()
        val connection = if (created != null || !command.isAttached) {
            ""
        } else {
            library.value.submit(HarnessIntent.Public.Attach(request(), id, context.session)).failureText()
                ?.let { "; not connected to this chat: $it" } ?: ", connected to this chat"
        }
        return created?.let { failure("not created: $it") }
            ?: AgentToolResult("Created harness ${command.name.value}$connection. Add items with harness_item_put.")
    }

    /** Metadata and scope are separate revisions; the second expects the revision the first committed. */
    private suspend fun update(
        context: AgentToolContext,
        harness: Harness,
        command: AuthoringCommand.Update,
        scope: HarnessScope?,
    ): AgentToolResult {
        val author = HarnessAuthor.Agent(context.session)
        val changes = listOfNotNull(
            HarnessChange.Meta(command.title ?: harness.title, command.description ?: harness.description)
                .takeIf { command.title != null || command.description != null },
            scope?.let(HarnessChange::Scope),
        )
        val failed = changes.withIndex().firstNotNullOfOrNull { (index, change) ->
            val revision = harness.revision + index
            val intent = HarnessIntent.Public.Update(request(), harness.id, change, revision, author, clock.now())
            library.value.submit(intent).failureText()
        }
        return failed?.let { failure("not updated: $it") } ?: AgentToolResult("Updated harness ${harness.name.value}")
    }

    private suspend fun edit(
        context: AgentToolContext,
        harness: Harness,
        change: HarnessChange,
        at: Instant,
        success: String,
    ): AgentToolResult {
        val author = HarnessAuthor.Agent(context.session)
        val intent = HarnessIntent.Public.Update(request(), harness.id, change, harness.revision, author, at)
        return outcome(intent, success)
    }

    private suspend fun outcome(intent: HarnessIntent.Public, success: String): AgentToolResult =
        library.value.submit(intent).failureText()?.let { failure("not saved: $it") } ?: AgentToolResult(success)

    private suspend fun scopeOf(context: AgentToolContext, scope: AuthoringScope): HarnessScope? = when (scope) {
        AuthoringScope.Attached -> HarnessScope.Attached

        AuthoringScope.Profile -> HarnessScope.Profile

        AuthoringScope.Project ->
            library.value.sourceProject(context.workspace)?.let { HarnessScope.Projects(setOf(it)) }
    }

    private fun itemText(owner: Harness, draft: ItemDraft, isNew: Boolean, check: CodeCheck.Compiled?): String =
        buildString {
            append(draft.kind.key.replaceFirstChar(Char::uppercase)).append(' ')
            append(owner.name.value).append('/').append(draft.name.value)
            append(if (isNew) " · new" else " · replaces the current version")
            if (check != null) append(" · compiled, warnings: ").append(check.warnings.size)
            append("\n\n").append(draft.body).append('\n')
            if (draft.description.isNotEmpty()) append("\nDescription: ").append(draft.description)
            if (draft.input.isNotEmpty()) append("\nInput schema: ").append(draft.input.toString())
        }

    private fun ready(
        state: HarnessState.Ready?,
        harness: Harness,
        title: String,
        text: String,
        command: AuthoringCommand,
        scope: HarnessScope? = null,
    ): Plan {
        val current = state ?: return Plan.Invalid("the harness library is unavailable")
        return Plan.Ready(
            command,
            harness,
            current.approval,
            AgentToolApproval(
                "",
                title,
                text.ifEmpty { null },
                "approval=${current.approval.key};harness=${harness.id.value}@${harness.revision}",
            ),
            scope = scope,
        )
    }

    private inline fun withHarness(state: HarnessState.Ready, name: String, block: (Harness) -> Plan): Plan {
        val harness = state.harnesses.map { it.harness }.singleOrNull { it.name.value == name }
            ?: return Plan.Invalid("no harness named $name; see harness_list")
        return block(harness)
    }

    private fun request() = RequestId(Uuid.random().toHexString())

    private fun failure(message: String) = AgentToolResult(message.replaceFirstChar(Char::uppercase), isError = true)

    private sealed interface Plan {
        data class Invalid(val message: String) : Plan

        data class Ready(
            val command: AuthoringCommand,
            val harness: Harness?,
            val level: HarnessApproval,
            val approval: AgentToolApproval,
            val isAlwaysAsked: Boolean = false,
            val scope: HarnessScope? = null,
            val item: HarnessItem? = null,
            val tools: ToolPolicySpec? = null,
        ) : Plan {
            override fun toString(): String = "Plan.Ready(***)"
        }
    }
}

private fun String.digest(): String = encodeUtf8().sha256().hex()

private const val NO_PROJECT = "this chat has no saved project; use scope attached or profile"
private const val ID_CHARS = 24
