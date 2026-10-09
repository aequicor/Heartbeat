package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.script.HARNESS_API_VERSION

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

    A harness is a reusable environment for a task or project. Build it when the user asks, e.g. "set up a harness
    for native Compose UI work". Steps:
    1. Read harness_tools_catalog to see Heartbeat and native tools; decide what the task needs.
    2. harness_create(name, title, description, scope, attach). Scope: attached (default, only connected chats),
       profile (every chat) or project (this chat's source project; worktrees count as the project).
    3. harness_item_put for each item:
       - instruction: a short rule every session follows (≤ ${HarnessLimits.INSTRUCTION_CHARS} chars);
       - skill: markdown knowledge loaded on demand by name (≤ ${HarnessLimits.SKILL_CHARS} chars), e.g. design
         tokens and component patterns; give it a description saying when to load it;
       - template: a step prompt with {{argument}} placeholders, rendered by harness_prompt_get or workflows;
       - script: Kotlin hooks, event handlers and own tools (Desktop only);
       - workflow: Kotlin orchestration of helper agents (Desktop only).
    4. harness_tools_set to turn Heartbeat tools off or switch native engine tools on/off. Turning a tool off is
       better than a Deny hook. Enabled native tools still go through the trust level of the chat.
    5. Check with harness_get, then tell the user what was set up.

    Approval: texts follow the profile approval level; code and enabling native tools always ask the user.
    Never store secrets, tokens or personal data in a harness. Keep instructions short: the whole harness block
    shares about 8000 characters per chat; skills are not in the block, only their index.
    Limits: ${HarnessLimits.HARNESSES} harnesses, ${HarnessLimits.ITEMS} items each, ${HarnessLimits.SCRIPT_TOOLS}
    script tools, ${HarnessLimits.TIMERS} timers (≥ 30 s), ${HarnessLimits.RUNS} concurrent workflow runs with
    ${HarnessLimits.HELPERS_PER_RUN} helpers, ${HarnessLimits.STEPS} steps and 6 hours per run.
""".trimIndent()

private val API = """
    Kotlin API (scripts: base class HarnessScriptBase; workflows: HarnessWorkflowBase). Imported by default:
    ItemName, events.on<…>, AgentOptions, launch, JsonElement/JsonObject/JsonPrimitive/JsonNull, buildJsonObject, put,
    seconds/minutes/hours. Import other types explicitly (package io.aequicor.heartbeat.feature.harness.api.*
    and io.aequicor.heartbeat.feature.aiengine.facade.api.*).

    Script top level runs once per activation (≤ 10 s) and only registers handlers:
    - script.harness / script.name / script.item / script.revision; script.scope for launch { } work.
    - hooks.beforePrompt { context, text -> String? } — extra context appended to the user's prompt.
    - hooks.beforeTool { call -> ToolHookVerdict } — Continue, Deny(reason) or Ask(reason); never allows.
      call.name, call.action (Read/Edit/Command), call.arguments (untrusted JsonObject), call.isNative.
    - hooks.afterTool { call, result -> String? } — a note shown to the agent before the result (≤ 2000 chars).
    - events.on<SessionEvent.TurnFinished> { event -> } — see topic events. Handlers run ≤ 30 s.
    - agent.tool(ItemName("check"), description, schema, action = AgentToolAction.Command) { call ->
        ScriptToolResult(text, isError) } — published as hs_<harness>_<name>; the schema is frozen once published.
    - agent.instructions { scope -> String } — dynamic guidance (≤ 0.5 s).
    - sessions.list(), sessions.history(session) — only chats where this harness is active.
    - sessions.send(session, text) — a visible message to that chat (quota ${HarnessLimits.SENDS_PER_MINUTE}/min).
    - sessions.spawn(parent, title, prompt) — a helper chat with Ask trust.
    - scheduler.every(5.minutes) { } / scheduler.at(instant) { } — in-process timers.
    - scheduler.wake(session, condition, note) / scheduler.cancel(id) — owned wakes of a chat.
    - scheduler.publish(ItemName("built"), payload) — bus event custom.harness.<harness>.<name>.
    - prompts.render(ItemName("step"), mapOf("arg" to "value")) — render a template of this harness.
    - workflows.start(ItemName("screen"), input) — start a workflow of this harness (covered by script approval).
    Hooks cannot send, spawn or start workflows. Not available: top-level suspend (use script.scope.launch),
    @Serializable, println, exitProcess, global coroutine scopes, blocking bridges and @file annotations.
    Five failures in a row disable the item. Exceptions are reported by type only.

    Workflow source registers one definition: workflow { input -> … JsonElement }. Inside (WorkflowScope):
    - agent(prompt, AgentOptions(title, target = null)): String — runs a helper chat and returns its answer;
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
    Queues keep the newest 64 events per handler; slow handlers lose old events.
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

/** Desktop workflow of the example harness: a design pass and an implementation pass by helper chats. */
internal val EXAMPLE_SCREEN_WORKFLOW = """
    workflow { input ->
        val screen = (input["screen"] as? JsonPrimitive)?.content ?: "Screen"
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
                JsonPrimitive(agent(brief, AgentOptions("Tests ${'$'}screen")))
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
    Worked example: harness compose_ui for native Compose UI.

    harness_create(name="compose_ui", title="Compose UI", description="Native Compose screens with the design
    system", scope="project")

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
    workflow splits design and implementation into helper chats; the script tightens destructive commands and
    adds a reminder after edits; the policy removes tools the task does not need.
""".trimIndent()
