package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.harness.api.Harness
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
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.CodeCheck
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeReviews
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.ItemDraft
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.ItemKind
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.Parsed
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.hasEnabledScript
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.hasNativeOn
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.hasRestrictions
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.kind
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.listing
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.map
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseCreate
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseHarnessRef
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseItemDelete
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseItemPut
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseToolsSet
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.parseUpdate
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.policyDiff
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.reEnabled
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Agent library mutations pass an independent automatic safety review. The complete proposed change, previous
 * harness, connections and trusted caller are bound to a cached verdict. Only safe permits execution; execution
 * never starts a new review or requests a user decision. Machine CAS checks protect revision and connection scope.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessAuthoringTools(
    private val toggles: FeatureToggles,
    private val library: Lazy<HarnessLibraryClient>,
    private val checks: Lazy<HarnessCodeChecks>,
    private val catalogs: Lazy<HarnessToolCatalogs>,
    private val clock: Clock,
    private val reviews: Lazy<HarnessCodeReviews>,
) : AgentToolContribution {
    private val log = Log.tag("HarnessTools")
    override val group: String = HarnessTools.GROUP
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
                add(
                    "Все изменения проходят независимую автоматическую проверку безопасности; код также " +
                        "компилируется. Безопасные изменения выполняются, риск, неопределённость или недоступность " +
                        "проверки возвращают ошибку с причиной. Не сохраняй секреты.",
                )
            }
            if (has(HarnessTools.TOOLS_SET)) {
                add("Чтобы убрать инструмент, выключай его через harness_tools_set, а не Deny-хуком.")
            }
        }.joinToString("\n")
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = when (val planned = plan(spec.name, arguments)) {
        is Plan.Ready -> harnessSafetySnapshot(context, planned.approval, checkNotNull(planned.reviewChange)).approval
        is Plan.Invalid -> AgentToolApproval(spec.name, spec.description, planned.message, binding = "safety-refused")
    }

    override suspend fun automaticAuthorization(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolAutomaticAuthorization {
        if (!toggles.get(HarnessEnabled)) return AgentToolAutomaticAuthorization.Denied("Harnesses are turned off")
        return when (val plan = reviewed(context, plan(spec.name, arguments), canStart = true)) {
            is Plan.Ready -> AgentToolAutomaticAuthorization.Allowed(plan.approval)
            is Plan.Invalid -> AgentToolAutomaticAuthorization.Denied(plan.message)
        }
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(HarnessEnabled)) return failure("harnesses are turned off")
        val plan = when (val planned = reviewed(context, plan(name, arguments), canStart = false)) {
            is Plan.Invalid -> {
                log.d { "Harness authoring request refused name=$name final=${planned.isFinal}" }
                return failure("not saved: ${planned.message}")
            }

            is Plan.Ready -> planned
        }
        if (context.authorization?.binding != plan.approval.binding) {
            log.d { "Harness authoring approval is stale name=$name" }
            return failure("the harness changed after safety review; request it again")
        }
        return apply(context, plan)
    }

    /** Reads the exact cached verdict at execution; profile approval and turn trust never bypass review. */
    private suspend fun reviewed(context: AgentToolContext, plan: Plan, canStart: Boolean): Plan {
        if (plan !is Plan.Ready) return plan
        val authorization = reviewHarnessChange(
            reviews.value,
            context,
            plan.approval,
            checkNotNull(plan.reviewChange),
            canStart,
        )
        return when (authorization) {
            is AgentToolAutomaticAuthorization.Allowed -> plan.copy(approval = authorization.approval)
            is AgentToolAutomaticAuthorization.Denied -> Plan.Invalid(authorization.reason, isFinal = true)
        }
    }

    private suspend fun plan(name: String, arguments: JsonObject): Plan {
        val command = when (val parsed = parse(name, arguments)) {
            is Parsed.Invalid -> return Plan.Invalid(parsed.message, isFinal = true)
            is Parsed.Valid -> parsed.value
        }
        val state = library.value.current ?: return Plan.Invalid("the harness library is unavailable")
        val plan = planCommand(state, command)
        if (plan !is Plan.Ready) return plan
        if (state.attachmentWrite != null || plan.harness?.id in state.pending) {
            return Plan.Invalid("the harness has a pending change; retry after it completes")
        }
        val connections = plan.harness?.let { owner -> state.attachments.filterValues { owner.id in it }.keys }
        val change = buildJsonObject {
            put("operation", name)
            put("arguments", arguments)
            put("previousHarness", Json.encodeToJsonElement(plan.harness))
            put("proposedItem", Json.encodeToJsonElement(plan.item))
            put("proposedTools", Json.encodeToJsonElement(plan.tools))
            put("connections", Json.encodeToJsonElement(connections.orEmpty().sortedBy { Json.encodeToString(it) }))
            put("consequences", plan.approval.description)
        }.toString()
        return plan.copy(
            approval = plan.approval.copy(name = name),
            reviewChange = change,
            connections = connections,
        )
    }

    private suspend fun planCommand(state: HarnessState.Ready, command: AuthoringCommand): Plan = when (command) {
        is AuthoringCommand.Create -> planCreate(state, command)

        is AuthoringCommand.Update -> withHarness(state, command.harness.value) { planUpdate(it, command) }

        is AuthoringCommand.Delete -> withHarness(state, command.harness.value) { planDelete(state, it, command) }

        is AuthoringCommand.Attach -> withHarness(state, command.harness.value) { planAttach(state, it, command) }

        is AuthoringCommand.PutItem -> withHarness(state, command.harness.value) { planItem(state, it, command) }

        is AuthoringCommand.DeleteItem -> withHarness(state, command.harness.value) {
            planItemDelete(state, it, command)
        }

        is AuthoringCommand.SetTools -> withHarness(state, command.harness.value) { planTools(state, it, command) }
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

    private fun planCreate(state: HarnessState.Ready, command: AuthoringCommand.Create): Plan {
        if (state.harnesses.any { it.harness.name == command.name } ||
            state.pending.values.any { it is HarnessMutation.Save && it.harness.name == command.name }
        ) {
            return Plan.Invalid("a harness named ${command.name.value} already exists; update it instead")
        }
        val text = buildString {
            append("New harness · active only in connected chats")
            if (command.isAttached) append(" · connected to this chat")
            append("\n\nTitle: ").append(command.title)
            if (command.description.isNotEmpty()) append("\nDescription: ").append(command.description)
        }
        return Plan.Ready(
            command,
            null,
            AgentToolApproval(
                "",
                "Create harness ${command.name.value}",
                text,
                "harness=new:${command.name.value}",
            ),
        )
    }

    /** Metadata is reviewed with the previous harness, like every agent mutation. */
    private fun planUpdate(harness: Harness, command: AuthoringCommand.Update): Plan {
        val text = buildString {
            command.title?.let { append("Title: ").append(it).append('\n') }
            command.description?.let { append("Description: ").append(it.ifEmpty { "(none)" }).append('\n') }
        }.trimEnd()
        return ready(library.value.current, harness, "Update harness ${harness.name.value}", text, command)
    }

    /** Review of script removal includes its previous Deny/Ask hooks in every connected chat. */
    private fun planItemDelete(
        state: HarnessState.Ready,
        harness: Harness,
        command: AuthoringCommand.DeleteItem,
    ): Plan {
        // Harness content is fenced by the bound revision, so a missing item stays missing at execution.
        val item = harness.items.singleOrNull { it.name == command.item }
            ?: return Plan.Invalid("no item ${command.item.value} in ${harness.name.value}", isFinal = true)
        val isLifting = item is HarnessItem.Script && item.isEnabled
        val text = if (isLifting) SCRIPT_REMOVED else ""
        val plan = ready(state, harness, "Delete item ${harness.name.value}/${item.name.value}", text, command)
        return plan
    }

    /** Review covers removal of tool restrictions, script hooks and running workflows. */
    private fun planDelete(state: HarnessState.Ready, harness: Harness, command: AuthoringCommand.Delete): Plan {
        val lifted = liftWarning(harness)
        val text = buildString {
            append("Deletes ${harness.items.size} items, its connections and runs; running workflows are cancelled.")
            if (lifted.isNotEmpty()) append('\n').append(lifted.trimEnd())
        }
        val plan = ready(state, harness, "Delete harness ${harness.name.value}", text, command)
        return plan
    }

    /**
     * Connecting spreads tools switched on and script access; disconnecting gives back tools the harness turned
     * off and drops its script hooks.
     */
    private fun planAttach(state: HarnessState.Ready, harness: Harness, command: AuthoringCommand.Attach): Plan {
        val warning = if (command.isAttached) spreadWarning(harness) else liftWarning(harness)
        val verb = if (command.isAttached) "Connect" else "Disconnect"
        val plan = ready(state, harness, "$verb harness ${harness.name.value}", warning.trimEnd(), command)
        return plan
    }

    /** What reaches more chats when the harness covers them; empty when nothing is affected. */
    private fun spreadWarning(harness: Harness): String = buildString {
        if (harness.tools.hasNativeOn) append(NATIVE_ON_SPREAD).append('\n')
        if (harness.hasEnabledScript) append(SCRIPT_SPREAD).append('\n')
    }

    /** What chats lose when the harness stops covering them; empty when nothing is affected. */
    private fun liftWarning(harness: Harness): String = buildString {
        if (harness.tools.hasRestrictions) append(RESTRICTIONS_LIFTED).append('\n')
        if (harness.hasEnabledScript) append(SCRIPT_HOOKS_LIFTED).append('\n')
    }

    private suspend fun planItem(
        state: HarnessState.Ready,
        harness: Harness,
        command: AuthoringCommand.PutItem,
    ): Plan {
        val draft = command.item
        val existing = harness.items.singleOrNull { it.name == draft.name }
        if (existing != null && existing.kind() != draft.kind) {
            val message = "item ${draft.name.value} is a ${existing.kind().key}; delete it first or rename"
            return Plan.Invalid(message, isFinal = true)
        }
        // A stable id lets the approval compile and the saved item share one cache entry.
        val id = existing?.id ?: ItemId("it_" + "${harness.id.value}/${draft.name.value}".digest().take(ID_CHARS))
        val check = when (val compiled = compile(harness, id, draft)) {
            is CodeCheck.Failed -> {
                return Plan.Invalid("compilation failed:\n" + compiled.diagnostics.listing(), isFinal = true)
            }

            CodeCheck.TimedOut -> return Plan.Invalid("compilation timed out; retry later")

            CodeCheck.Unsupported -> {
                return Plan.Invalid("Kotlin scripts and workflows run only on Desktop", isFinal = true)
            }

            is CodeCheck.Compiled -> compiled

            null -> null
        }
        val title = "Save ${draft.kind.key} ${draft.name.value} in ${harness.name.value}"
        val plan = ready(state, harness, title, itemText(harness, draft, existing == null, check), command)
        val item = draft.toItem(id, existing?.isEnabled ?: true)
        return (plan as Plan.Ready).copy(
            item = item,
        )
    }

    /** Code is compiled before review; texts need no compilation and yield null. */
    private suspend fun compile(harness: Harness, id: ItemId, draft: ItemDraft): CodeCheck? {
        if (!draft.kind.isCode) return null
        val kind = if (draft.kind == ItemKind.Script) HarnessCodeKind.Script else HarnessCodeKind.Workflow
        return checks.value.check(harness.id, id, kind, draft.body, isAgent = true)
    }

    /**
     * The agent replaces the policy, except switches of engines that are not available now: the agent cannot see
     * them in the catalog, so they are kept rather than silently dropped.
     */
    private fun planTools(state: HarnessState.Ready, harness: Harness, command: AuthoringCommand.SetTools): Plan {
        val registered = catalogs.value.engines().map { it.id.value }.toSet()
        val kept = harness.tools.native.filterKeys { it !in registered && it !in command.native }
        val next = ToolPolicySpec(command.hostedOff, command.native + kept)
        catalogs.value.problem(harness.tools, next)?.let { return Plan.Invalid(it) }
        val enabled = reEnabled(harness.tools, next)
        val diff = policyDiff(harness.tools, next).ifEmpty { listOf("No changes") }
        val text = buildString {
            append(diff.joinToString("\n"))
            if (kept.isNotEmpty()) {
                append("\n\nKept switches of engines unavailable now: ").append(kept.keys.sorted().joinToString())
            }
            if (enabled.isNotEmpty()) {
                append("\n\nTools switched on or given back run under the trust level of each chat and its hooks.")
            }
        }
        val plan = ready(state, harness, "Tool policy of ${harness.name.value}", text, command) as Plan.Ready
        // Kept switches depend on the engines registered now, so the decision covers the exact resulting policy.
        val binding = plan.approval.binding + ";tools=" + next.fingerprint()
        return plan.copy(
            approval = plan.approval.copy(binding = binding),
            tools = next,
        )
    }

    private suspend fun apply(context: AgentToolContext, plan: Plan.Ready): AgentToolResult {
        val at = clock.now()
        val harness = plan.harness
        return when (val command = plan.command) {
            is AuthoringCommand.Create -> create(context, command)

            is AuthoringCommand.Update -> update(context, plan, command)

            is AuthoringCommand.Delete -> outcome(
                HarnessIntent.Internal.Delete(
                    request(),
                    checkNotNull(harness).id,
                    harness.revision,
                    plan.connections,
                ),
                "Deleted harness ${harness.name.value}",
            )

            is AuthoringCommand.Attach -> {
                val id = checkNotNull(harness).id
                val intent = if (command.isAttached) {
                    HarnessIntent.Public.Attach(request(), id, context.session, harness.revision, plan.connections)
                } else {
                    HarnessIntent.Public.Detach(request(), id, context.session, harness.revision, plan.connections)
                }
                outcome(intent, if (command.isAttached) "Connected to this chat" else "Disconnected from this chat")
            }

            is AuthoringCommand.PutItem -> edit(
                context,
                plan,
                HarnessChange.PutItem(checkNotNull(plan.item)),
                at,
                "Saved ${command.item.kind.key} ${checkNotNull(harness).name.value}/${command.item.name.value}",
            )

            is AuthoringCommand.DeleteItem -> {
                val item = checkNotNull(harness).items.first { it.name == command.item }
                edit(context, plan, HarnessChange.RemoveItem(item.id), at, "Deleted item ${command.item.value}")
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
                edit(context, plan, HarnessChange.Tools(plan.tools), at, "Saved tool policy.$note")
            }
        }
    }

    private suspend fun create(context: AgentToolContext, command: AuthoringCommand.Create): AgentToolResult {
        val id = HarnessId("h_" + Uuid.random().toHexString())
        val draft = HarnessDraft(command.name, command.title, command.description, HarnessScope.Attached)
        val intent = HarnessIntent.Internal.Create(request(), id, draft, context.session, clock.now())
        val created = library.value.submit(intent).failureText()
        val connection = if (created != null || !command.isAttached) {
            ""
        } else {
            val attach = HarnessIntent.Public.Attach(request(), id, context.session, 0, emptySet())
            library.value.submit(attach).failureText()?.let { "; not connected to this chat: $it" }
                ?: ", connected to this chat"
        }
        return created?.let { failure("not created: $it") }
            ?: AgentToolResult("Created harness ${command.name.value}$connection. Add items with harness_item_put.")
    }

    private suspend fun update(
        context: AgentToolContext,
        plan: Plan.Ready,
        command: AuthoringCommand.Update,
    ): AgentToolResult {
        val harness = checkNotNull(plan.harness)
        val change = HarnessChange.Meta(command.title ?: harness.title, command.description ?: harness.description)
        val author = HarnessAuthor.Agent(context.session)
        val intent = HarnessIntent.Internal.Update(
            request(),
            harness.id,
            change,
            harness.revision,
            author,
            clock.now(),
            plan.connections,
        )
        return library.value.submit(intent).failureText()?.let { failure("not updated: $it") }
            ?: AgentToolResult("Updated harness ${harness.name.value}")
    }

    private suspend fun edit(
        context: AgentToolContext,
        plan: Plan.Ready,
        change: HarnessChange,
        at: Instant,
        success: String,
    ): AgentToolResult {
        val harness = checkNotNull(plan.harness)
        val author = HarnessAuthor.Agent(context.session)
        val intent = HarnessIntent.Internal.Update(
            request(),
            harness.id,
            change,
            harness.revision,
            author,
            at,
            plan.connections,
        )
        return outcome(intent, success)
    }

    private suspend fun outcome(intent: HarnessIntent.Command, success: String): AgentToolResult =
        library.value.submit(intent).failureText()?.let { failure("not saved: $it") } ?: AgentToolResult(success)

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
    ): Plan {
        if (state == null) return Plan.Invalid("the harness library is unavailable")
        return Plan.Ready(
            command,
            harness,
            AgentToolApproval(
                "",
                title,
                text.ifEmpty { null },
                "harness=${harness.id.value}@${harness.revision}",
            ),
        )
    }

    private inline fun withHarness(state: HarnessState.Ready, name: String, block: (Harness) -> Plan): Plan {
        // Ids are never reused, so a harness missing now cannot match the revision an approval was bound to.
        val harness = state.harnesses.map { it.harness }.singleOrNull { it.name.value == name }
            ?: return Plan.Invalid("no harness named $name; see harness_list", isFinal = true)
        return block(harness)
    }

    private fun request() = RequestId(Uuid.random().toHexString())

    private fun failure(message: String) = AgentToolResult(message.replaceFirstChar(Char::uppercase), isError = true)

    private sealed interface Plan {
        /**
         * [isFinal] marks refusals execution repeats deterministically: malformed arguments, names missing from the
         * bound revision and code that failed to compile. Other refusals rest on state that may change before
         * execution.
         */
        data class Invalid(val message: String, val isFinal: Boolean = false) : Plan

        data class Ready(
            val command: AuthoringCommand,
            val harness: Harness?,
            val approval: AgentToolApproval,
            val item: HarnessItem? = null,
            val tools: ToolPolicySpec? = null,
            val reviewChange: String? = null,
            val connections: Set<SessionRef>? = null,
        ) : Plan {
            override fun toString(): String = "Plan.Ready(***)"
        }
    }
}

private fun String.digest(): String = encodeUtf8().sha256().hex()

/** Unambiguous digest of a whole policy: every name is length-prefixed, entries are sorted. */
private fun ToolPolicySpec.fingerprint(): String = buildString {
    fun field(value: String) = append(value.length).append(':').append(value)
    hostedOff.sorted().forEach {
        append('h')
        field(it)
    }
    native.keys.sorted().forEach { engine ->
        native.getValue(engine).entries.sortedBy { it.key }.forEach { (tool, switch) ->
            append('n')
            field(engine)
            field(tool)
            append(switch.name)
        }
    }
}.digest().take(ID_CHARS)

private const val NATIVE_ON_SPREAD = "Native tools switched on by this harness become available in more chats."
private const val RESTRICTIONS_LIFTED =
    "Tools turned off by this harness become available again where it stops applying."
private const val SCRIPT_SPREAD =
    "Enabled scripts of this harness start running in more chats: their hooks see prompts and tool calls there."
private const val SCRIPT_HOOKS_LIFTED =
    "Hooks of enabled scripts stop guarding tool calls in chats this harness no longer applies to."
private const val SCRIPT_REMOVED = "An enabled script is removed: its hooks stop guarding tool calls in every chat."
private const val ID_CHARS = 24
