package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.script.HARNESS_API_VERSION
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HARNESS_CONTEXT_CHARS
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.EVENT_QUEUE_CAPACITY
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HOOK_RESULT_CHARS
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInvocationBudget

/** Topics of harness_api_reference; the examples are compiled by a desktop test against the current API. */
internal enum class HarnessApiReference(val key: String) {
    Guide("guide"),
    Api("api"),
    Events("events"),
    Examples("examples"),
}

internal fun referenceText(topic: HarnessApiReference): String = when (topic) {
    HarnessApiReference.Guide -> GUIDE
    HarnessApiReference.Api -> API
    HarnessApiReference.Events -> EVENTS
    HarnessApiReference.Examples -> EXAMPLES
}

private val GUIDE = """
    Harness authoring guide (API v$HARNESS_API_VERSION).

    A harness is a reusable environment for a task or project, including an embedded mini-app. Steps:
    1. For scripts, read harness_api_reference(topic="api") and topic="examples" before writing code.
    2. harness_create(name, title, description, attach). It belongs to the current project (a worktree uses its
       source project), or the shared library when created without a project. The attach flag only controls
       connection to this chat; it does not change that library scope. Other chats connect it explicitly
       from the composer’s + menu or by the agent with harness_attach.
    3. harness_item_put for each item:
       - instruction: a short rule every session follows (≤ ${HarnessLimits.INSTRUCTION_CHARS} chars);
       - skill: markdown knowledge loaded on demand by name (≤ ${HarnessLimits.SKILL_CHARS} chars), e.g. design
         tokens and component patterns; give it a description saying when to load it;
       - template: a step prompt with {{argument}} placeholders, rendered by harness_prompt_get or workflows;
       - script: Kotlin hooks, event handlers and own tools (Desktop only);
       - workflow: Kotlin orchestration of helper agents (Desktop only).
       Submit changes to the same harness sequentially. If a mutation reports a stale snapshot/scope, read
       harness_get again, reconcile the change with the current state, then retry it.
    4. If the task needs a tool policy, read harness_tools_catalog, then use harness_tools_set to turn Heartbeat
       tools off or switch native engine tools on/off. Enabling a tool never bypasses the host safety checks.
    5. Check with harness_get, then tell the user what was set up.

    Embedded mini-app path:
    - Read the built-in skills with ui_reference(topic="mini-app-design") for design and topic="service-as-ui"
      for rendering choices, placements and examples. Use topic="spec" for the exact format.
      If the session's ui_reference schema has no topic argument, call it without arguments for all documents.
    - Create the harness with attach=true (or harness_attach an existing harness to this chat).
    - Read the local mini-app in harness_api_reference(topic="examples"). Save its methods with
      harness_item_put(kind="script").
      This tool compiles Kotlin before saving. On "compilation failed", fix the reported source line and submit
      the complete corrected source with the SAME harness and item name. A failed compile does not save the item.
      Do not create another harness/item for a syntax fix. No shell, Gradle, project files or native tools are
      needed for this loop; enabling bash cannot fix a Kotlin type error. Resolve the first error before chasing
      follow-up errors on expressions that depend on it.
    - Check harness_get and ui_list for the published owner and methods. Use data for durable runtime values;
      the snapshot method only reads and supplies defaults. The examples topic includes a bounded record list.
    - Create an owner-backed surface with ui_put and placement="card", then
      ui_show(title="Records", surface=returned_id).
      Keep the existing chat and input. The host creates the app's composer button and full start view.
      Add placement="context" only for a useful separate summary or quick action, not a duplicate app.
      See ui_reference for the exact spec, data sources and web bridge. A saved surface alone is not visible.

    Safety: every agent mutation passes independent automatic review of the exact caller, content, previous
    source and affected chats. Code also compiles before review. Only a safe verdict permits execution;
    risk, uncertainty or unavailable review returns a tool error with a reason, without asking the user.
    Fix the concrete issue before retrying; profile approval levels and full trust never bypass this gate.
    Off switches and Deny/Ask hooks apply in every chat a harness is connected to; everything else comes from the
    first ${HarnessLimits.ACTIVE_PER_SESSION} connected harnesses of a chat by name.
    Never put secrets, tokens or personal data in harness source, instructions or templates. Runtime mini-app
    values belong in data (the protected profile store); do not use script fields or browser localStorage for
    persistent state. Keep instructions short: the whole harness block
    shares $HARNESS_CONTEXT_CHARS characters per chat; skills are not in the block, only their index.
    Limits: ${HarnessLimits.HARNESSES} harnesses, ${HarnessLimits.ITEMS} items each, ${HarnessLimits.SCRIPT_TOOLS}
    script tools, ${HarnessLimits.TIMERS} timers (≥ ${HarnessLimits.MIN_TIMER}), ${HarnessLimits.RUNS} concurrent
    workflow runs with ${HarnessLimits.HELPERS_PER_RUN} helpers, ${HarnessLimits.STEPS} steps and
    ${HarnessLimits.RUN_TIME} per run.
""".trimIndent()

private val API = """
    Kotlin API (scripts: base class HarnessScriptBase; workflows: HarnessWorkflowBase). Imported by default:
    ItemName, events.on<…>, AgentOptions, AdaptiveAgent, AgentPriority, AgentComplexity, AgentNeed, ScriptUiResult,
    ScriptDataSnapshot, ScriptDataWriteResult, launch,
    JsonElement/JsonObject/JsonArray/JsonPrimitive/JsonNull, buildJsonObject/buildJsonArray, put/add/addJsonObject/putJsonArray/putJsonObject, seconds/minutes/hours.
    Import other types explicitly (package io.aequicor.heartbeat.feature.harness.api.*
    and io.aequicor.heartbeat.feature.aiengine.facade.api.*).

    Every ItemName, including ui.method, agent.tool and scheduler.publish names, must match
    [a-z][a-z0-9_]{0,31}: lowercase snake_case only, never camelCase. Use ItemName("save_record"), not
    ItemName("saveRecord"). Invalid names compile but fail during activation with InvalidItemName.
    harness_item_put confirms storage; activation is asynchronous. Read harness_get and require active before
    calling methods. A failed status includes known host validation reasons; correct the script and put it again.

    JSON recipes (Kotlin, not JavaScript):
    - Builders always take a lambda: buildJsonArray { } and buildJsonObject { }. buildJsonArray() is invalid.
      Constructor alternatives are JsonArray(emptyList()) and JsonObject(emptyMap()).
    - put("count", 0), put("title", "Ready"), put("enabled", true) use primitive overloads.
      put("items", JsonArray(emptyList())) and put("selection", JsonNull) insert JSON values.
      For nullable or mixed branches, choose JsonElement explicitly before put, for example:
        val selection: JsonElement = call.arguments["selection"] ?: JsonNull
        val state = buildJsonObject { put("selection", selection) }
      Use JsonNull for a JSON null, not put("selection", null), which is ambiguous between primitive overloads.
    - Read untrusted values with safe casts: (call.arguments["label"] as? JsonPrimitive)?.contentOrNull.
      Import kotlinx.serialization.json.contentOrNull, intOrNull, longOrNull or doubleOrNull explicitly as needed;
      these accessors are not default imports. data.read("state") returns a snapshot: JSON is in snapshot.value.
      ScriptDataSnapshot.value is nullable, including after write/update. For a JSON field use
      put("state", snapshot.value ?: JsonNull), and for a result use ScriptUiResult.Value(snapshot.value ?: JsonNull).

    Script top level runs once per activation (≤ ${HarnessInvocationBudget.Evaluation}) and only registers handlers:
    - script.harness / script.name / script.item / script.revision; script.scope for launch { } work.
    - hooks.beforePrompt { context, text -> String? } — extra context appended to the user's prompt.
    - hooks.beforeTool { call -> ToolHookVerdict } — Continue, Deny(reason) or Ask(reason); never allows.
      call.name, call.action (Read/Edit/Command), call.arguments (untrusted JsonObject), call.isNative.
    - hooks.afterTool { call, result -> String? } — a note shown to the agent before the result (≤ $HOOK_RESULT_CHARS chars).
    - events.on<SessionEvent.TurnFinished> { event -> } — see topic events.
      Handlers run ≤ ${HarnessInvocationBudget.Event}.
    - agent.tool(ItemName("check"), description, schema, action = AgentToolAction.Command) { call ->
        ScriptToolResult(text, isError) } — published as hs_<harness>_<name>; the schema is frozen once published.
    - agent.instructions { scope -> String } — dynamic guidance (≤ ${HarnessInvocationBudget.Instructions}).
    - ui.method(ItemName("prs"), description) { call -> ScriptUiResult.Value(json) or ScriptUiResult.Error(text) } —
      a method the harness's interfaces (ui_put surfaces, see ui_reference) call when the user presses a button,
      without an agent turn. call.arguments is untrusted JSON; call.session and call.workspace are null outside a
      chat. Runs ≤ ${HarnessInvocationBudget.UiMethod} and is cancelled when the user leaves; the JSON value is at most
      ${HarnessLimits.UI_RESULT_BYTES} bytes; at most ${HarnessLimits.UI_METHODS} methods. Return ScriptUiResult.Error
      for expected problems (missing login, bad input): thrown exceptions count as failures. Put UI methods in their
      own script item: an activation runs its callbacks one at a time, so a slow method delays hooks of its item.
    - data.read("state"): ScriptDataSnapshot(value: JsonElement?, revision: Long) — durable JSON shared by all
      script items of this harness. Source/item replacement, detach, disable and restart keep data; deleting the
      harness removes it. Each call requires the current published activation; evaluation cannot read or write.
    - data.write("state", jsonOrNull, expectedRevision): ScriptDataWriteResult.Written(snapshot) or Conflict(current).
      Revision is namespace-wide; Conflict does not write. Null deletes a key; JsonNull stores JSON null.
    - data.update("state") { old -> newJsonOrNull }: ScriptDataSnapshot — atomic optimistic read/modify/write.
      The transform must be pure and quick, because concurrent changes can run it again. Never perform IO inside.
      Keys: [a-z][a-z0-9_]{0,63}, at most ${HarnessLimits.DATA_KEYS} keys / ${HarnessLimits.DATA_BYTES} UTF-8 bytes
      per harness including metadata. Invalid keys/quotas throw IllegalArgumentException before changing data.
      Only successful changed writes invalidate the owner's surfaces; a snapshot/read method must not initialize
      data by writing on every load. Use defaults on read, initialize through an explicit user action.
    - sessions.list(), sessions.history(session) — only chats where this harness is active.
    - sessions.send(session, text) — a visible message to that chat (quota ${HarnessLimits.SENDS_PER_MINUTE}/min).
    - sessions.spawn(parent, title, prompt, adaptive = null) — a helper chat with Ask trust; adaptive picks its
      model like AgentOptions.adaptive below.
    - scheduler.every(5.minutes) { } / scheduler.at(instant) { } — in-process timers.
    - scheduler.wake(session, condition, note) / scheduler.cancel(id) — owned wakes of a chat; a wake shares the
      ${HarnessLimits.SENDS_PER_MINUTE}/min quota with sessions.send.
    - scheduler.publish(ItemName("built"), payload) — bus event custom.harness.<harness>.<name>.
    - prompts.render(ItemName("step"), mapOf("arg" to "value")) — render a template of this harness.
    - workflows.start(ItemName("screen"), input) — start a workflow of this harness (covered by script approval).
    Hooks cannot send, wake, spawn or start workflows. Not available: top-level suspend (use script.scope.launch),
    @Serializable, println, exitProcess, global coroutine scopes, blocking bridges and @file annotations.
    Harness turns in a chat: after ${HarnessLimits.SEND_CHAIN} agent turns in a row started by harnesses (sends,
    wakes and their follow-ups) without a turn of the user, sessions.send and scheduler.wake throw
    IllegalStateException and pending script wakes of that chat are dropped until the user writes again.
    ${HarnessLimits.FAILURES} failures in a row disable the item. Exceptions are reported by type only. Refusals of
    the turn limit above are not failures: an uncaught one ends the callback without counting towards the limit.

    Workflow source registers one definition: workflow { input -> … JsonElement }. Inside (WorkflowScope):
    - agent(prompt, AgentOptions(title, target = null, adaptive = null)): String — runs a helper chat and returns
      its answer. target pins the model; otherwise adaptive = AdaptiveAgent(priority, complexity, needs, tags) lets
      the host choose engine, model and reasoning effort among the user's enabled models when adaptive subagents
      are on: priority Quality/Balanced/Speed/Cost (null — the user's default), complexity Low/Medium/High, needs
      Images/Pdf/LongContext, tags such as coding, review, research, docs. Without both, or when nothing fits,
      the helper keeps the default model. The model is chosen once per step and kept on replay and recovery;
      changing adaptive for a recorded step fails the run as Diverged;
    - parallel({ … }, { … }) — branches run concurrently;
    - step("name") { … JsonElement } — memoized local work; now() — memoized clock;
    - prompt(ItemName("t"), args) / skill(ItemName("s")) — pinned content of the harness.
    Runs survive restarts: finished steps replay from the journal, so keep the call order deterministic and put
    nondeterministic work inside step { }. A changed prompt for a recorded step fails the run as Diverged.
    The result is JSON, at most ${HarnessLimits.RESULT_CHARS} characters.
""".trimIndent()

private val EVENTS = """
    Events (package io.aequicor.heartbeat.feature.harness.api.event), subscribe with events.on<Type> { }:
    - SystemEvent.Started, SystemEvent.NetworkChanged(isConnected) — network only with the scheduler enabled.
    - SchedulerEvent.WakeScheduled / Woke / WakeFailed / WakesCancelled / WakeRejected — no wake notes.
    - HarnessBusEvent(key, payload) — every bus event; payload is untrusted text.
    - SessionEvent.Opened / TurnStarted / TurnFinished(outcome) / PermissionRequested(permission) / Closed —
      only for chats where this harness is active; context has session, workspace, request and turn.
    - EngineEvent.AvailabilityChanged / ConnectionsChanged.
    - HarnessLifecycleEvent.Activated, HarnessLifecycleEvent.WorkflowFinished(run, status).
    Queues keep the newest $EVENT_QUEUE_CAPACITY events per script; slow handlers lose old events.
""".trimIndent()

/** Desktop script of the example harness: blocks destructive commands and reminds about checks after edits. */
internal val EXAMPLE_VERIFY_SCRIPT = """
    import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
    import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
    import io.aequicor.heartbeat.feature.harness.api.event.SessionEvent
    import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult

    val destructive = Regex("\\brm\\s+-rf\\b|\\bgit\\s+push\\s+--force\\b")

    hooks.beforeTool { call ->
        val command = (call.arguments["command"] as? JsonPrimitive)?.content.orEmpty()
        if (call.action == AgentToolAction.Command && destructive.containsMatchIn(command)) {
            ToolHookVerdict.Deny("Destructive commands are blocked by the compose_ui harness")
        } else {
            ToolHookVerdict.Continue
        }
    }

    hooks.afterTool { call, result ->
        val path = (call.arguments["path"] as? JsonPrimitive)?.content.orEmpty()
        if (!result.isError && call.action == AgentToolAction.Edit && path.endsWith("Screen.kt")) {
            "Screen changed: run hs_compose_ui_check before finishing."
        } else {
            null
        }
    }

    agent.tool(
        ItemName("check"),
        "Explain how to verify a Compose screen of this project",
        buildJsonObject { put("type", "object") },
        AgentToolAction.Read,
    ) { _ ->
        ScriptToolResult(prompts.render(ItemName("verify_steps")))
    }

    events.on<SessionEvent.TurnFinished> { _ -> }
""".trimIndent()

/** Desktop script of a pull request map: a UI method lists PRs of a repository with dependencies and a diagram. */
internal val EXAMPLE_UI_SCRIPT = """
    import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiCall
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import kotlinx.serialization.json.Json
    import kotlinx.serialization.json.jsonArray
    import kotlinx.serialization.json.jsonObject
    import kotlinx.serialization.json.jsonPrimitive

    val repository = Regex("[\\w.-]+/[\\w.-]+")

    fun field(pr: JsonObject, name: String): String = pr[name]?.jsonPrimitive?.content.orEmpty()

    fun pullRequests(call: ScriptUiCall): ScriptUiResult {
        val repo = call.arguments["repo"]?.jsonPrimitive?.content.orEmpty()
        if (!repository.matches(repo)) return ScriptUiResult.Error("Enter the repository as owner/name")
        val process = ProcessBuilder(
            "gh", "pr", "list", "--repo", repo, "--limit", "50", "--json", "number,title,url,headRefName,baseRefName",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) return ScriptUiResult.Error("gh failed; sign in with: gh auth login")
        val prs = Json.parseToJsonElement(output).jsonArray.map { it.jsonObject }
        val byBranch = prs.associateBy { field(it, "headRefName") }
        val items = buildJsonArray {
            prs.forEach { pr ->
                addJsonObject {
                    put("number", field(pr, "number"))
                    put("title", field(pr, "title"))
                    put("url", field(pr, "url"))
                    put("dependsOn", byBranch[field(pr, "baseRefName")]?.let { field(it, "number") }.orEmpty())
                }
            }
        }
        val diagram = buildString {
            appendLine("@startuml")
            prs.forEach { pr ->
                val title = field(pr, "title").replace('"', '\'')
                appendLine("rectangle \"#" + field(pr, "number") + " " + title + "\" as PR_" + field(pr, "number"))
            }
            prs.forEach { pr ->
                byBranch[field(pr, "baseRefName")]?.let { base ->
                    appendLine("PR_" + field(base, "number") + " <-- PR_" + field(pr, "number"))
                }
            }
            append("@enduml")
        }
        return ScriptUiResult.Value(
            buildJsonObject {
                put("items", items)
                put("plantuml", diagram)
                put("count", prs.size)
            },
        )
    }

    ui.method(ItemName("prs"), "Open PRs of {repo: owner/name} with dependencies and a PlantUML map") { call ->
        withContext(Dispatchers.IO) { pullRequests(call) }
    }
""".trimIndent()

/** Local mini-app methods: read-only snapshots, bounded record history and revision-checked reset. */
internal val EXAMPLE_MINIAPP_SCRIPT = """
    import kotlinx.serialization.json.contentOrNull
    import kotlinx.serialization.json.longOrNull

    fun records(value: JsonElement?): JsonArray {
        val state = value as? JsonObject
        return state?.get("records") as? JsonArray ?: JsonArray(emptyList())
    }

    fun view(snapshot: ScriptDataSnapshot): JsonObject = buildJsonObject {
        put("records", records(snapshot.value))
        put("count", records(snapshot.value).size)
        put("revision", snapshot.revision)
    }

    ui.method(ItemName("snapshot"), "Read the saved progress without changing it") {
        ScriptUiResult.Value(view(data.read("progress")))
    }
    ui.method(ItemName("record"), "Append {label: string, note?: string}; keep the latest 20 records") { call ->
        val label = (call.arguments["label"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val note = (call.arguments["note"] as? JsonPrimitive)?.contentOrNull
        if (label.isEmpty() || label.length > 80 || (note?.length ?: 0) > 160) {
            ScriptUiResult.Error("Use a label of 1-80 characters and an optional note of at most 160")
        } else {
            val noteJson: JsonElement = note?.let { JsonPrimitive(it) } ?: JsonNull
            val entry = buildJsonObject {
                put("label", label)
                put("note", noteJson)
            }
            val saved = data.update("progress") { previous ->
                buildJsonObject { put("records", JsonArray(records(previous).takeLast(19) + entry)) }
            }
            ScriptUiResult.Value(view(saved))
        }
    }
    ui.method(ItemName("reset"), "Reset only the revision the user reviewed") { call ->
        val expected = (call.arguments["revision"] as? JsonPrimitive)?.longOrNull
        if (expected == null || expected < 0) {
            ScriptUiResult.Error("Reload progress before resetting it")
        } else {
            when (val result = data.write("progress", null, expected)) {
                is ScriptDataWriteResult.Written -> ScriptUiResult.Value(view(result.snapshot))
                is ScriptDataWriteResult.Conflict -> ScriptUiResult.Error("Progress changed; review it again")
            }
        }
    }
""".trimIndent()

/** Desktop workflow of the example harness: a design pass and an implementation pass by helper chats. */
internal val EXAMPLE_SCREEN_WORKFLOW = """
    workflow { input ->
        val screen = (input["screen"] as? JsonPrimitive)?.content ?: "Screen"
        val checklist = AdaptiveAgent(AgentPriority.Cost, AgentComplexity.Low, tags = setOf("review"))
        val design = agent(
            prompt(ItemName("design_step"), mapOf("screen" to screen)),
            AgentOptions("Design ${'$'}screen"),
        )
        val results = parallel(
            {
                val brief = "Implement ${'$'}screen following:\n${'$'}design"
                JsonPrimitive(agent(brief, AgentOptions("Implement ${'$'}screen")))
            },
            {
                val brief = "List previews and tests needed for ${'$'}screen"
                JsonPrimitive(agent(brief, AgentOptions("Tests ${'$'}screen", adaptive = checklist)))
            },
        )
        buildJsonObject {
            put("design", design)
            put("implementation", results[0])
            put("tests", results[1])
        }
    }
""".trimIndent()

private val EXAMPLES = """
    Local mini-app: a durable record list. Create and attach a harness, then save this as a script item "methods".
    It needs no project, shell, files, network or extra native tools. Input values arrive in call.arguments and
    stay in the protected data store; do not embed the user's personal values in the source.
    Use snapshot as the surface's auto data source, record for its action button, and pass the displayed revision
    to reset. A web card calls heartbeat.call("record", {label: "Example", note: null}); native buttons call the
    same method. The example retains only the latest 20 entries. Visible owner surfaces reload after a changed
    durable write. Reading an empty store returns defaults without writing.

${EXAMPLE_MINIAPP_SCRIPT.prependIndent("    ")}

    Next: ui_put with this harness as owner and placement="card", then ui_show with the returned surface id.
    Follow ui_reference for the surface JSON and bridge; add a compact composer status only if useful.

    Advanced project workflow: harness compose_ui for native Compose UI.

    harness_create(name="compose_ui", title="Compose UI", description="Native Compose screens with the design
    system")

    instruction "tokens": "Use only design-system tokens and Hb* components; no raw colors, dp or sp literals."
    skill "design_system" (description "Load before writing a screen"): markdown with token names, layouts,
    component catalog, preview rules.
    template "design_step": "Propose the layout of {{screen}}: components, states, previews. Answer as a list."
    template "verify_steps": "Run detekt and the screen tests; fix every finding before finishing."
    workflow "screen" with input {"type":"object","properties":{"screen":{"type":"string"}},"required":["screen"]}:

${EXAMPLE_SCREEN_WORKFLOW.prependIndent("    ")}

    script "verify":

${EXAMPLE_VERIFY_SCRIPT.prependIndent("    ")}

    harness_tools_set(harness="compose_ui", hosted_off=["web_search"], native={"claude": {"WebFetch": "off"}})

    Why: the instruction keeps every chat on tokens; the skill carries detail without filling the prompt; the
    workflow splits design and implementation into helper chats, and the cheap test checklist may run on a
    lighter model chosen by the host; the script tightens destructive commands and
    adds a reminder after edits; the policy removes tools the task does not need.

    Advanced external integration: only when the task actually needs repository data and gh is already available
    and authenticated. This example runs an external command and receives its own automatic safety review;
    it is not a prerequisite or a setup step for local mini-apps. Harness pr_map gets script "methods", then a
    surface built with ui_put calls it from a button without another agent turn:

${EXAMPLE_UI_SCRIPT.prependIndent("    ")}
""".trimIndent()
