package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwnerInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceContent
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import io.aequicor.heartbeat.feature.serviceasui.api.UiWrite
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOp
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.data.RESULT_BYTES
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiAuthoringKnowledge
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardLimits
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.WriteOutcome
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiNode
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecCheck
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecRules
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.bodies
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.checkUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.children
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.submit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Agent tools of the interface library: the format reference, reading the library and owners, and creating,
 * replacing and deleting interfaces. Mutations and agent method calls require independent automatic safety
 * review bound to the exact proposal; a refusal returns to the agent without an interactive permission request.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ServiceUiAgentTools(
    private val machine: Lazy<ServiceUiMachine>,
    private val calls: Lazy<UiMethodCalls>,
    private val cards: Lazy<UiCardStorage>,
    private val toggles: FeatureToggles,
    private val clock: Clock,
    private val safety: ServiceUiToolSafety,
    private val knowledge: UiAuthoringKnowledge,
) : AgentToolContribution {
    private val log = Log.tag("ServiceUiTools")

    override val group: String = ServiceUiTools.GROUP
    override val title: String = "Интерфейсы"
    override val isDetachedSupported: Boolean = true
    override val catalog: List<ToolCatalogEntry> get() = ServiceUiToolSpecs.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(ServiceUiEnabled)) ServiceUiToolSpecs else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String =
        if (toggles.get(ServiceUiEnabled)) INSTRUCTIONS else ""

    override suspend fun instructions(scope: AgentToolScope): String {
        val declared = scope.declared
        return if (declared == null || ServiceUiTools.PUT in declared) instructions(scope.workspace) else ""
    }

    override suspend fun automaticAuthorization(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolAutomaticAuthorization? {
        if (spec.name !in REVIEWED_TOOLS) return null
        if (!toggles.get(ServiceUiEnabled)) return AgentToolAutomaticAuthorization.Denied(NOT_LOADED)
        return when (val proposal = safetyPlan(context, spec, arguments)) {
            is SafetyPlan.Invalid -> AgentToolAutomaticAuthorization.Denied(proposal.message)
            is SafetyPlan.Ready -> safety.authorize(context, proposal.request)
        }
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = if (spec.name in REVIEWED_TOOLS) {
        when (val proposal = safetyPlan(context, spec, arguments)) {
            is SafetyPlan.Ready -> proposal.request.approval
            is SafetyPlan.Invalid -> AgentToolApproval(spec.name, spec.description, proposal.message, "invalid")
        }
    } else {
        unreviewedApproval(spec, arguments)
    }

    private suspend fun unreviewedApproval(spec: AgentToolSpec, arguments: JsonObject): AgentToolApproval =
        when (val plan = plan(spec.name, arguments)) {
            is Plan.Write -> plan.approval.copy(name = spec.name)

            is Plan.Invalid -> AgentToolApproval(
                spec.name,
                spec.description,
                "Request will be refused: ${plan.message}",
            )

            Plan.None -> when (spec.name) {
                ServiceUiTools.SHOW -> AgentToolApproval(
                    spec.name,
                    "Show the card “${arguments.text("title").orEmpty()}”",
                    arguments["spec"]?.let { PrettyJson.encodeToString(JsonElement.serializer(), it) },
                )

                ServiceUiTools.CALL -> AgentToolApproval(
                    spec.name,
                    "Call ${arguments.text("method").orEmpty()} of ${arguments.text("owner").orEmpty()}",
                    (arguments["args"] as? JsonObject)?.let { PrettyJson.encodeToString(JsonObject.serializer(), it) },
                )

                else -> AgentToolApproval(spec.name, spec.description)
            }
        }

    /** Rebuilt before authorization and execution; never evaluates an owner's script. */
    private suspend fun safetyPlan(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject): SafetyPlan =
        when (spec.name) {
            ServiceUiTools.PUT, ServiceUiTools.DELETE -> writeSafetyPlan(context, spec, arguments)
            ServiceUiTools.SHOW -> showSafetyPlan(context, spec, arguments)
            ServiceUiTools.CALL -> callSafetyPlan(context, spec, arguments)
            else -> SafetyPlan.Invalid("unknown interface tool")
        }

    private suspend fun writeSafetyPlan(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): SafetyPlan {
        val ready = ready() ?: return SafetyPlan.Invalid(NOT_LOADED)
        val plan = when (spec.name) {
            ServiceUiTools.PUT -> putPlan(ready, arguments)
            else -> deletePlan(ready, arguments)
        }
        if (plan !is Plan.Write) return SafetyPlan.Invalid((plan as? Plan.Invalid)?.message ?: "invalid operation")
        val previous = when (plan) {
            is Plan.Put -> ready.library[plan.content.id]
            is Plan.Delete -> ready.library[plan.surface]
        }
        val expected = when (plan) {
            is Plan.Put -> plan.expected
            is Plan.Delete -> plan.expected
        }
        if (expected != (previous?.revision ?: 0L)) {
            return SafetyPlan.Invalid("not saved: the interface changed; reload it and use its current revision")
        }
        val snapshot = buildJsonObject {
            put("previous", Json.encodeToJsonElement(previous))
            if (plan is Plan.Put) put("proposed", Json.encodeToJsonElement(plan.content))
        }
        return SafetyPlan.Ready(
            uiToolSafetyRequest(
                context,
                plan.approval.copy(name = spec.name),
                arguments,
                snapshot,
                libraryRevision = ready.library.revision,
            ),
        )
    }

    private suspend fun showSafetyPlan(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): SafetyPlan {
        if (arguments.text("title")?.length !in 1..ServiceUiLimits.TITLE_CHARS) {
            return SafetyPlan.Invalid("title is required, at most ${ServiceUiLimits.TITLE_CHARS} characters")
        }
        val surface = arguments.text("surface")
        val inline = inlineSpec(arguments)
        if ((inline == null) == (surface == null)) return SafetyPlan.Invalid("give either spec or surface")
        if (inline is UiSpecCheck.Invalid) {
            return SafetyPlan.Invalid(
                "the spec is invalid:\n" + inline.diagnostics.joinToString("\n") { it.describe() },
            )
        }
        val shownSurface = if (surface != null) {
            val ready = ready() ?: return SafetyPlan.Invalid(NOT_LOADED)
            ready.library.surfaces.firstOrNull { it.id.value == surface && it.isEnabled }
                ?: return SafetyPlan.Invalid("no enabled interface $surface")
        } else {
            null
        }
        val snapshot = if (shownSurface != null) {
            buildJsonObject { put("surface", Json.encodeToJsonElement(shownSurface)) }
        } else {
            buildJsonObject { put("spec", requireNotNull(arguments["spec"])) }
        }
        if (arguments["args"]?.toString().orEmpty().encodeToByteArray().size > ServiceUiLimits.ARGS_BYTES) {
            return SafetyPlan.Invalid("args are too large")
        }
        return SafetyPlan.Ready(
            uiToolSafetyRequest(context, unreviewedApproval(spec, arguments), arguments, snapshot),
            shownSurface,
        )
    }

    private suspend fun callSafetyPlan(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): SafetyPlan {
        val ready = ready() ?: return SafetyPlan.Invalid(NOT_LOADED)
        val owner = try {
            owner(ready, requireNotNull(arguments.text("owner")) { "owner is required" })
        } catch (error: IllegalArgumentException) {
            log.w(error.redactedFailure()) { "Interface method review refused" }
            return SafetyPlan.Invalid(error.message ?: "unknown owner")
        }
        val method = arguments.text("method") ?: return SafetyPlan.Invalid("method is required")
        if (arguments["args"]?.toString().orEmpty().encodeToByteArray().size > ServiceUiLimits.ARGS_BYTES) {
            return SafetyPlan.Invalid("args are too large")
        }
        val review = calls.value.review(owner, method, UiCallContext(context.session, context.workspace))
            ?: return SafetyPlan.Invalid("method source is unavailable for automatic safety review")
        val snapshot = buildJsonObject {
            put("owner", Json.encodeToJsonElement(owner))
            put("method", method)
            put("source", review.source)
        }
        return SafetyPlan.Ready(
            uiToolSafetyRequest(context, unreviewedApproval(spec, arguments), arguments, snapshot, review.binding),
        )
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (!toggles.get(ServiceUiEnabled)) return failure("interfaces are turned off")
        val proposal = if (name in REVIEWED_TOOLS) {
            when (val planned = authorizedPlan(context, name, arguments)) {
                is SafetyPlan.Invalid -> return failure(planned.message)
                is SafetyPlan.Ready -> planned
            }
        } else {
            null
        }
        val authorized = proposal?.let { context.copy(authorization = it.request.original) } ?: context
        return when (name) {
            ServiceUiTools.REFERENCE -> serviceUiReference(arguments, knowledge, RESULT_BYTES, CALL_SECONDS)

            ServiceUiTools.LIST -> list()

            ServiceUiTools.GET -> get(arguments)

            ServiceUiTools.PUT, ServiceUiTools.DELETE ->
                write(authorized, name, arguments, proposal?.request?.libraryRevision)

            ServiceUiTools.SHOW -> show(authorized, arguments, proposal?.shownSurface)

            ServiceUiTools.CALL -> callMethod(authorized, arguments, proposal?.request?.methodBinding)

            else -> failure("unknown interface tool")
        }
    }

    private suspend fun authorizedPlan(context: AgentToolContext, name: String, arguments: JsonObject): SafetyPlan {
        val plan = safetyPlan(context, ServiceUiToolSpecs.first { it.name == name }, arguments)
        return if (plan is SafetyPlan.Ready && !safety.isAuthorized(context, plan.request)) {
            SafetyPlan.Invalid(
                "the interface changed while awaiting approval or safety review expired; request it again",
            )
        } else {
            plan
        }
    }

    private suspend fun list(): AgentToolResult {
        val ready = ready() ?: return failure(NOT_LOADED)
        val owners = coroutineScope {
            ready.owners.flatMap { (kind, owners) -> owners.owners.map { kind to it } }
                .map { (kind, info) -> async { ownerJson(kind, info) } }
                .awaitAll()
        }
        val result = buildJsonObject {
            put("surfaces", buildJsonArray { ready.library.surfaces.forEach { add(it.summary()) } })
            put("owners", buildJsonArray { owners.forEach { add(it) } })
        }
        return AgentToolResult(result.toString())
    }

    private suspend fun ownerJson(kind: String, info: UiOwnerInfo): JsonElement {
        val methods = withTimeoutOrNull(METHODS_TIMEOUT) { calls.value.methods(UiOwner(kind, info.id)).first() }
        return buildJsonObject {
            put("owner", "$kind:${info.id}")
            put("name", info.name)
            put("title", info.title)
            put("enabled", info.isEnabled)
            if (methods?.isAvailable == true) {
                putJsonArray("methods") {
                    methods.methods.forEach { method ->
                        add(
                            buildJsonObject {
                                put("name", method.name)
                                put("description", method.description)
                            },
                        )
                    }
                }
            } else {
                put("methods", "unavailable: its script is not running here")
            }
        }
    }

    private fun get(arguments: JsonObject): AgentToolResult {
        val ready = ready() ?: return failure(NOT_LOADED)
        val id = arguments.text("id") ?: return failure("id is required")
        val surface = ready.library.surfaces.firstOrNull { it.id.value == id } ?: return failure("no interface $id")
        val spec = try {
            Json.parseToJsonElement(surface.content.spec)
        } catch (e: IllegalArgumentException) {
            log.w(e.redactedFailure()) { "Stored spec of $id is unreadable" }
            JsonPrimitive(surface.content.spec)
        }
        val result = buildJsonObject {
            surface.summary().forEach { (key, value) -> put(key, value) }
            put("description", surface.content.description)
            put("spec", spec)
        }
        return AgentToolResult(result.toString())
    }

    private suspend fun show(
        context: AgentToolContext,
        arguments: JsonObject,
        reviewedSurface: UiSurface?,
    ): AgentToolResult {
        val title = arguments.text("title")?.takeIf { it.length <= ServiceUiLimits.TITLE_CHARS }
            ?: return failure("title is required, at most ${ServiceUiLimits.TITLE_CHARS} characters")
        val surface = arguments.text("surface")
        val inline = inlineSpec(arguments)
        val source = when {
            (inline == null) == (surface == null) -> return failure("give either spec or surface")

            inline is UiSpecCheck.Invalid -> return failure(
                "the spec is invalid:\n" + inline.diagnostics.joinToString("\n") { it.describe() },
            )

            inline != null -> UiCardSource.Inline(requireNotNull(arguments["spec"]).toString())

            else -> {
                val ready = ready() ?: return failure(NOT_LOADED)
                val found = ready.library.surfaces.firstOrNull { it.id.value == surface && it.isEnabled }
                    ?: return failure("no enabled interface ${surface.orEmpty()}")
                // Reading the safety memo can suspend. Admit the same initial snapshot after that wait;
                // the stored reference remains live, so later owner-approved updates still reach this card.
                if (found != reviewedSurface) {
                    return failure("the interface changed after safety review; request it again")
                }
                UiCardSource.Surface(found.id)
            }
        }
        val args = arguments["args"] as? JsonObject ?: JsonObject(emptyMap())
        if (args.toString().encodeToByteArray().size > ServiceUiLimits.ARGS_BYTES) return failure("args are too large")
        val card = UiCard(
            id = "c${Uuid.random().toHexString()}",
            chat = context.session,
            callId = context.callId?.value,
            title = title,
            source = source,
            args = args,
            createdAt = clock.now(),
        )
        return when (val stored = cards.value.add(card)) {
            is UiCardAdded.Stored -> AgentToolResult(
                ServiceUiTools.shown(stored.id, isLive = source is UiCardSource.Surface),
            )

            UiCardAdded.ChatFull -> failure("this chat already shows ${UiCardLimits.PER_CHAT} cards; no more fit")
        }
    }

    /** The inline spec of a `ui_show` call checked as a card without an owner, or null without one. */
    private fun inlineSpec(arguments: JsonObject): UiSpecCheck? =
        (arguments["spec"] as? JsonObject)?.let { checkUiSpec(it.toString(), UiSpecRules(UiSpecOrigin.Inline)) }

    private suspend fun callMethod(
        context: AgentToolContext,
        arguments: JsonObject,
        expectedBinding: String?,
    ): AgentToolResult {
        if (expectedBinding == null) return failure("method safety review is unavailable")
        val ready = ready() ?: return failure(NOT_LOADED)
        val owner = try {
            owner(ready, requireNotNull(arguments.text("owner")) { "owner is required" })
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Interface method call refused" }
            return failure(e.message ?: "unknown owner")
        }
        val method = arguments.text("method") ?: return failure("method is required")
        val args = arguments["args"] as? JsonObject ?: JsonObject(emptyMap())
        val call = UiCallContext(session = context.session, workspace = context.workspace)
        return when (val result = calls.value.call(owner, UiMethodCall(method, args, call, expectedBinding))) {
            is UiMethodResult.Success -> AgentToolResult(result.value.toString().take(CALL_RESULT_CHARS))
            is UiMethodResult.UserError -> failure("the method reported: ${result.message}")
            is UiMethodResult.Failure -> failure("the call failed: ${result.reason.name}")
        }
    }

    private suspend fun write(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        expectedLibraryRevision: Long?,
    ): AgentToolResult {
        if (expectedLibraryRevision == null) return failure("library safety review is unavailable")
        val plan = plan(name, arguments)
        if (plan !is Plan.Write) return failure((plan as? Plan.Invalid)?.message ?: "unknown interface tool")
        if (context.authorization?.binding != plan.approval.binding) {
            log.d { "Interface approval is stale name=$name" }
            return failure("the interface changed while awaiting approval; request it again")
        }
        val op = when (plan) {
            is Plan.Put -> UiWriteOp.Put(plan.content, plan.expected, context.session, clock.now())
            is Plan.Delete -> UiWriteOp.Delete(plan.surface, plan.expected)
        }
        val write = UiWrite(
            "agent-${Uuid.random().toHexString()}",
            UiWriteOrigin.Agent,
            op,
            expectedLibraryRevision = expectedLibraryRevision,
        )
        return when (val outcome = machine.value.submit(write)) {
            is WriteOutcome.Applied -> AgentToolResult(plan.success(outcome.surface))
            else -> failure("not saved: ${outcome.failureText()}")
        }
    }

    private suspend fun plan(name: String, arguments: JsonObject): Plan = when (name) {
        ServiceUiTools.PUT -> ready()?.let { putPlan(it, arguments) } ?: Plan.Invalid(NOT_LOADED)
        ServiceUiTools.DELETE -> ready()?.let { deletePlan(it, arguments) } ?: Plan.Invalid(NOT_LOADED)
        else -> Plan.None
    }

    private suspend fun putPlan(ready: ServiceUiState.Ready, arguments: JsonObject): Plan {
        val draft = try {
            draft(ready, arguments)
        } catch (e: IllegalArgumentException) {
            // The agent's arguments break a rule of the content; it learns which one.
            log.w(e) { "Interface put refused" }
            return Plan.Invalid(e.message ?: "invalid arguments")
        }
        val existing = ready.library[draft.content.id]
        if (existing != null && draft.expected == 0L) {
            return Plan.Invalid(
                "interface ${existing.id.value} exists at revision ${existing.revision}; " +
                    "pass expectedRevision ${existing.revision} to replace it",
            )
        }
        val methods = withTimeoutOrNull(METHODS_TIMEOUT) { calls.value.methods(draft.content.owner).first() }
            ?.takeIf { it.isAvailable }
            ?.methods?.mapTo(mutableSetOf()) { it.name }
        val names = ready.library.surfaces.filter { it.content.owner == draft.content.owner }
            .mapTo(mutableSetOf()) { it.content.name.value } + draft.content.name.value
        val rules = UiSpecRules(UiSpecOrigin.Library, draft.content.placement, methods, names)
        return when (val check = checkUiSpec(draft.content.spec, rules)) {
            is UiSpecCheck.Invalid -> Plan.Invalid(
                "the spec is invalid:\n" + check.diagnostics.joinToString("\n") { it.describe() },
            )

            is UiSpecCheck.Valid -> {
                val hasWeb = check.spec.bodies.any { it.hasWeb() }
                Plan.Put(
                    content = draft.content,
                    expected = draft.expected,
                    warnings = check.warnings.map { it.describe() },
                    approval = putApproval(draft.content, draft.expected, hasWeb),
                )
            }
        }
    }

    private fun draft(ready: ServiceUiState.Ready, arguments: JsonObject): Draft {
        val owner = owner(ready, requireNotNull(arguments.text("owner")) { "owner is required" })
        val spec = requireNotNull(arguments["spec"] as? JsonObject) { "spec must be a JSON object" }
        val placement = UiPlacement.entries.firstOrNull { it.name.equals(arguments.text("placement"), true) }
        val launchers = (arguments["launchers"] as? JsonArray).orEmpty().map { slot ->
            val value = (slot as? JsonPrimitive)?.content
            requireNotNull(
                UiSlot.entries.firstOrNull { it.name.equals(value, true) },
            ) { "unknown launcher ${value.orEmpty()}" }
        }
        val content = UiSurfaceContent(
            owner = owner,
            name = UiSurfaceName(requireNotNull(arguments.text("name")) { "name is required" }),
            placement = requireNotNull(
                placement,
            ) { "placement must be card, composer, start, context, panel or modal" },
            title = requireNotNull(arguments.text("title")) { "title is required" },
            description = arguments.text("description").orEmpty(),
            launchers = launchers.toSet(),
            spec = spec.toString(),
        )
        val expected = (arguments["expectedRevision"] as? JsonPrimitive)?.longOrNull ?: 0L
        require(expected >= 0) { "expectedRevision cannot be negative" }
        return Draft(content, expected)
    }

    /** The owner named by its ref (`kind:id`) or, when unique, by its name. */
    private fun owner(ready: ServiceUiState.Ready, reference: String): UiOwner {
        val known = ready.owners.flatMap { (kind, owners) -> owners.owners.map { UiOwner(kind, it.id) to it } }
        val found = known.filter { (owner, info) ->
            "${owner.kind}:${owner.id}" == reference || info.name == reference
        }
        require(found.size == 1) {
            if (found.isEmpty()) {
                "no owner $reference; see ui_list"
            } else {
                "owner name $reference is ambiguous; use its ref"
            }
        }
        return found.single().first
    }

    private fun deletePlan(ready: ServiceUiState.Ready, arguments: JsonObject): Plan {
        val id = arguments.text("id") ?: return Plan.Invalid("id is required")
        val expected = (arguments["expectedRevision"] as? JsonPrimitive)?.longOrNull
            ?: return Plan.Invalid("expectedRevision is required")
        val surface = ready.library.surfaces.firstOrNull { it.id.value == id } ?: return Plan.Invalid(
            "no interface $id",
        )
        val approval = AgentToolApproval(
            name = "",
            title = "Delete interface “${surface.content.title}”",
            description = "${surface.id.value}, ${surface.content.placement.name.lowercase()}",
            binding = "ui_delete:$id@$expected",
        )
        return Plan.Delete(surface.id, expected, approval)
    }

    private fun putApproval(content: UiSurfaceContent, expected: Long, hasWeb: Boolean): AgentToolApproval {
        val launchers = content.launchers.map { it.name.lowercase() }.sorted()
        val description = buildString {
            append("Owner: ${content.owner.kind}:${content.owner.id}\n")
            append("Placement: ${content.placement.name.lowercase()}")
            if (launchers.isNotEmpty()) append(", launchers: ${launchers.joinToString()}")
            append('\n')
            if (hasWeb) append("Contains a web document.\n")
            if (content.description.isNotEmpty()) append(content.description).append('\n')
            append(
                '\n',
            ).append(PrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(content.spec)))
        }
        val fingerprint = listOf(
            content.owner.kind,
            content.owner.id,
            content.name.value,
            content.placement.name,
            content.title,
            content.description,
            launchers.joinToString(","),
            content.spec,
        ).joinToString("") { "${it.length}:$it" }.encodeUtf8().sha256().hex()
        return AgentToolApproval(
            name = "",
            title = "${if (expected == 0L) "Create" else "Replace"} interface “${content.title}”",
            description = description,
            binding = "ui_put:${content.id.value}@$expected:$fingerprint",
        )
    }

    private fun ready(): ServiceUiState.Ready? = machine.value.state.value as? ServiceUiState.Ready

    private fun failure(message: String) = AgentToolResult(message.replaceFirstChar(Char::uppercase), isError = true)

    private data class Draft(val content: UiSurfaceContent, val expected: Long)

    private sealed interface SafetyPlan {
        data class Invalid(val message: String) : SafetyPlan

        /** The initial displayed surface is checked again after awaiting the safety memo. */
        data class Ready(val request: UiToolSafetyRequest, val shownSurface: UiSurface? = null) : SafetyPlan {
            override fun toString(): String = "SafetyPlan.Ready(***)"
        }
    }

    /** What a tool call would do, computed alike for its approval and its execution. */
    private sealed interface Plan {
        data object None : Plan

        data class Invalid(val message: String) : Plan

        sealed interface Write : Plan {
            val approval: AgentToolApproval

            fun success(surface: UiSurface?): String
        }

        data class Put(
            val content: UiSurfaceContent,
            val expected: Long,
            val warnings: List<String>,
            override val approval: AgentToolApproval,
        ) : Write {
            override fun success(surface: UiSurface?): String = buildString {
                append("Saved interface ${content.id.value} at revision ${surface?.revision ?: "?"}.")
                if (warnings.isNotEmpty()) append("\n").append(warnings.joinToString("\n"))
            }

            override fun toString(): String = "Plan.Put(***)"
        }

        data class Delete(val surface: UiSurfaceId, val expected: Long, override val approval: AgentToolApproval) :
            Write {
            override fun success(surface: UiSurface?): String = "Deleted interface ${this.surface.value}."
        }
    }

    private companion object {
        const val CALL_SECONDS = 60
        const val CALL_RESULT_CHARS = 16_000
        const val NOT_LOADED = "interfaces are turned off or not loaded yet"
        val METHODS_TIMEOUT = 3.seconds
        val PrettyJson = Json { prettyPrint = true }
        val REVIEWED_TOOLS = setOf(ServiceUiTools.PUT, ServiceUiTools.DELETE, ServiceUiTools.SHOW, ServiceUiTools.CALL)
        const val INSTRUCTIONS =
            "Interfaces: before creating or redesigning a mini-app, load the bundled mini-app-design skill with " +
                "ui_reference(topic=mini-app-design). Load ui_reference(topic=service-as-ui) for rendering choices, " +
                "placements and examples; it routes to focused references. " +
                "Read ui_reference(topic=spec) for the schema; no arguments reads all bundled documents. " +
                "Prefer native nodes; use web for custom views with host theme tokens. " +
                "Create one canonical ui_put placement=card, then ui_show(surface=id). " +
                "Update the existing surface; the host supplies its chat entry, composer button, start view " +
                "and modal. " +
                "Keep the chat shell; add context only for a useful summary or quick action. Buttons call ui.method " +
                "without a model turn. Persist records with harness data; auto sources refresh on durable writes. " +
                "ui_list shows owners/methods; ui_put returns diagnostics; ui_delete removes a surface."
    }
}

private fun UiNode.hasWeb(): Boolean = this is UiNode.Web || children.any { it.hasWeb() }

private fun UiSurface.summary(): JsonObject = buildJsonObject {
    put("id", id.value)
    put("owner", "${content.owner.kind}:${content.owner.id}")
    put("name", content.name.value)
    put("placement", content.placement.name.lowercase())
    put("title", content.title)
    put("launchers", buildJsonArray { content.launchers.forEach { add(it.name.lowercase()) } })
    put("enabled", isEnabled)
    put("revision", revision)
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
