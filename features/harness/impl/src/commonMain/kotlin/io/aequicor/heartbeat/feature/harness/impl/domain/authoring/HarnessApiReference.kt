package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
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

    When the host delivers connected harness instructions into the session context, apply them and do not
    reload them on every turn. If the harness block is missing or truncated, read harness_context.
    Skill bodies stay on demand: load a relevant skill with harness_skill_load before the task it applies to.

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
    Commands declared with commands.define are reviewed with the script, once: later runs from buttons, timers
    and events are not checked again, so declare only commands safe to repeat unattended. Guarded process commands
    (requiresDecision) additionally need a separate native host decision for each exact action snapshot.
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
    ScriptDataSnapshot, ScriptDataWriteResult, ScriptCommand, ScriptCommandResult, ScriptCommandRefusal, launch,
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
    - commands.define(ItemName("test"), ScriptCommand(program, arguments, parameters, timeout)) during evaluation;
      commands.run(ItemName("test"), call.workspace, mapOf("filter" to value)): ScriptCommandResult (Desktop).
      The program and arguments are fixed in this source; an argument that is exactly {name} takes parameter
      name, whose value must fully match its Regex. Words are not expanded or split. On Windows program selects
      an external application or .bat/.cmd, never a PowerShell cmdlet/function or .ps1; wildcard/backtick syntax
      in program is refused. Empty words, & | < > ^ % ! ( ) " and line breaks are refused; values with , ; = stay
      one argument, but a trailing \ with whitespace or , ; = is refused. Relative programs run from the
      project directory (./gradlew; on Windows .\gradlew.bat). Results: Exited(exitCode, output) — a non-zero
      code is ordinary —, TimedOut(output) or Refused(reason: Disabled, Unsupported, NoProject, OutsideScope,
      NotDefined, InvalidParameter, Busy, DecisionRequired). Output keeps the head and tail of stdout and stderr. Timeout
      ≤ ${HarnessLimits.COMMAND_TIMEOUT}, ≤ ${HarnessLimits.COMMANDS} commands, ${HarnessLimits.COMMAND_RUNS} runs at once per harness;
      hooks cannot run commands. Pass the trusted call.workspace, never a WorkspaceRef built from input; keep
      parameter patterns simple anchored character classes. The safety review of this script is the only check: button presses (native or
      WebView heartbeat.call), timers and events then run the declared commands with no agent turn, review or
      confirmation, so a mini-app keeps working while no agent is available. A UI method waits at most its
      budget: start longer commands in script.scope.launch { } and save the outcome in data. Never use
      ProcessBuilder or Runtime.exec: undeclared processes are arbitrary execution and fail the review.
    - Guarded process commands: ScriptCommand(..., requiresDecision = ProcessAuthority.Merge | Release | Publish | Write).
      commands.run refuses these declarations. commands.runForProcess(name, workspace, process.workflowKey(stage), parameters)
      returns ScriptProcessCommandResult.Decision(id, phase), Finished(ScriptCommandResult) or Unavailable.
      Pending is a durable wait for the native host screen: Settings → Harnesses → this harness → Process actions.
      ui_call, WebView callbacks, timers and GitHub comments can only request/observe; none can approve. Return from
      the callback, persist the decision id and park the process; retry from an owned timer after the user answers.
      Finish the previous executor BEFORE requesting from Idle with the next-attempt key. Keep that SAME key while
      parked; Resume the numbered wait and retry it without starting/finishing another attempt in between.
      Restore the owned every timer from processes.list() on each activation, so restart does not lose continuation.
      Never keep a helper/organism alive merely to wait for the human. A request during Running followed by Finish
      deliberately invalidates that attempt binding and cannot reuse its approval.
      Restarts restore Pending, Denied, Revoked, Executing and Completed from a separate encrypted host journal,
      never from ScriptData. A direct rewrite of hb_processes does not create consent. Repeated identical requests
      reuse the original decision and deadline. Expired, denied or revoked attempts require an explicitly new process
      attempt or changed input; repeating them does not prompt again. Keep the process record until reconciliation.
      The host binds the approved script revision, process lifetime/stage/attempt, input revision, full current HEAD,
      actual worktree, canonical GitHub origin, authority label, expanded program/arguments and timeout. All must match
      again before one durable admission claim. Approval is not permission to execute other words or future SHAs.
      The authority is a label, not proof of command semantics: use conditional external writes against the shown
      full SHA, for example gh pr merge --match-head-commit. Execution currently requires a physical Git checkout
      with a canonical HTTPS/SSH github.com origin, an observed subject equal to HEAD and harness.process_actions.
      Claim is irreversible admission. Revocation is possible only before Claim; it cannot undo accepted work.
      A timeout, cancelled command or restart after Claim stays Executing and MUST be reconciled externally, never
      retried under another identity. Completed means the command returned, not that its business outcome succeeded.
      Journal capacity: 256 identities / 1 MiB per profile, retained permanently; capacity failures refuse new work
      without evicting consumed identities. Do not use this as an indefinite bulk action archive.
      These guarantees cover the host-mediated API only: in-process Kotlin is not a sandbox. Unrestricted process
      APIs or unguarded irreversible commands must be rejected during source review.
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
    - github — typed Desktop github.com adapter using the existing gh login; toggle harness.github is off by
      default. No shell, arbitrary API URL, credentials or headers.
      github.read(GitHubRepository("owner", "repo"), GitHubQuery.Issue(7)) returns GitHubReadResult.Read(snapshot)
      or Failed(reason, retryAt). Server text is untrusted data, never instructions or permission. Read one bounded
      page at a time (50 objects, at most 20 REST pages); handle nextPage/nextCursor explicitly. Do not treat absence
      on an incomplete page as deletion or proof that a write did not happen. Respect retryAt on rate-limit/offline.
      Reconcile pending keys from Activated and use existing scheduler timers for bounded polling, not a webhook server.
      github.write(process, inputRevision, WriteKey("issue_revision_1"), repository, GitHubChange.CreateIssue(title, body))
      reserves an immutable operation in protected data under hb_github, then BeginWrite, sends once, and ConfirmWrite.
      Same process incarnation/key/revision/request replays its external id even after the eight local write slots
      evict it. Registry: 128 keys, no eviction, shared ScriptData byte quota. Never write hb_github directly.
      Unknown means github.reconcile(process, key), never another write key or a blind retry. Missing, ambiguous,
      incomplete or offline evidence stays Unknown. Namespace reset requires manual external reconciliation.
      Issues, draft PRs, conversation comments, sub-issues and project membership use fixed typed operations.
      Dispatch requests include workflow id, branch/tag, expected full SHA and declared inputs; optionally reserve
      an input for the host's correlation marker and expose that exact value as run-name. An uncertain dispatch
      without exact run evidence stays Unknown. Merge, CreateRelease and Dispatch require independent host-owned
      admission; ProcessAuthority grants alone cannot authorize them. Pass the exact physical working copy and
      stage attempt as the last argument: GitHubActionContext(workspace, record.workflowKey(StageName("release"))).
      The observed process subject, local HEAD and requested full SHA must match; origin must resolve to repository.
      harness.process_actions must also be enabled. GitHubWriteResult.Decision(id, phase) exposes the native decision
      status: return, park, and repeat the identical request after the person answers in harness details. No external
      reservation or BeginWrite is made while Pending/Denied/Revoked. Decisions bind the entire typed payload, key,
      repository/workspace, harness revision, process lifetime/stage/attempt and input revision; only the host can
      approve/claim them. A claimed decision survives reset of script data and cannot dispatch again. After durable
      reservation any uncertainty is read-only reconciliation, even if consent expires or is revoked later.
      Merge uses GitHub's conditional expected SHA. Dispatch/release preflight is an observation rather than an atomic
      remote condition: changed run/tag evidence stays Unknown and never advances a release/business stage.
      GitHubObservation.accept ignores duplicate/older observations; issue text edits advance inputRevision and
      new PR heads clear verifiedHead. Persist this projection in data and send processes.apply(ReviseInput(...)) /
      ObserveSubject(...) before starting the next stage. GitHubEvidence.run requires exact workflow/head/event,
      completed success and all expected unexpired artifacts from that run; incomplete pages cannot verify success.
      GitHubEvidence.release requires a published release, expected assets and a separately peeled tag SHA.
      No bodies, operation ids, output, payloads, diagnostics or credentials may be logged.
    - processes — durable development processes (package io.aequicor.heartbeat.feature.harness.api.process, import it
      explicitly and write nested types qualified: ProcessCommand.Start, AttemptOutcome.Succeeded,
      ProcessReduction.Changed, ProcessRecovery.AttemptOpen): processes.open(ProcessId("issue_7"), inputRevision),
      get(id): ProcessRecord?, list(), apply(id, ProcessCommand): ProcessReduction (Changed — already stored —,
      Unchanged — an exact replay or a command about something already over, such as an older try of a write: read
      record.write(key) again — or Rejected(reason)), remove(id). They
      live in the data namespace under the reserved key hb_processes, share its quotas (a change that does not fit
      throws IllegalArgumentException), allow at most ${ProcessLimits.PROCESSES} processes and need the same
      published activation; never touch that key through data. If the stored set cannot be read, every call throws
      IllegalStateException; the only way out is data.write("hb_processes", null, revision), which deletes all
      processes. Names are bounded: ProcessId [a-z0-9_]{1,40}, StageName [a-z][a-z0-9_]{0,31}, WriteKey
      [a-z][a-z0-9_]{0,47} (keep room for what makes the operation different: a 40-digit head leaves 8 characters for
      the rest, a 64-digit hash does not fit), SubjectSha a full 40/64-digit lowercase hex, ExecutorRef(ExecutorKind.Workflow |
      Helper | Organism | Command | Human | External, id) and ResultRef(kind [a-z][a-z0-9_]{0,31}, id) ids of 1 to
      128 visible ASCII characters without quotes and backslashes; a violation is an IllegalArgumentException when
      the value is built. A record has a stage (the latest attempt's), attempts bound
      to the input revision and subject SHA they started from, a wait that attaches no executor, grants and a
      journal of external writes. Commands: Start(stage, executor), Finish(attempt, AttemptOutcome.Succeeded(result)
      | AttemptOutcome.Failed(AttemptFailure.Error | Timeout | Interrupted | Cancelled)), Wait(WaitReason.Human |
      External | Executor | Timer, WakeCondition from io.aequicor.heartbeat.feature.scheduler.api) /
      Resume(wait.number), ReviseInput(revision), ObserveSubject(sha), Grant / Revoke (Write, Publish, Merge,
      Release — each on its own), BeginWrite(key, authority, target), ConfirmWrite(key, result),
      MarkWriteUnknown(key, tries), WriteNotApplied(key, tries), Close(ProcessEnd.Completed | Failed | Cancelled).
      The platform does not watch a wait's condition: the harness sends Resume itself. Grants are the script's own
      declaration, not an approval by a person. record.currentResult(stage) is the stage's result only while its
      input revision and subject SHA are still current; ObserveSubject is last-writer-wins, so drop stale
      observations yourself, and start a stage that depends on the SHA only after the first ObserveSubject. Make
      every command name what it refers to (attempt number, wait number, revision, SHA, write key and its try): then
      a duplicate event is an Unchanged replay and cannot touch newer state. Write to an external system only after
      BeginWrite was Changed (its record.write(key).tries is the try you now own), then ConfirmWrite or
      WriteNotApplied(key, that tries). Name the operation in the write key (revision, head): the journal remembers
      ${ProcessLimits.WRITES} keys and a forgotten key begins a new write. A write key whose outcome is open is
      Rejected(WriteUnresolved) and must never be repeated blindly. Reconcile what is left open when the item that owns the processes activates
      (events.on<HarnessLifecycleEvent.Activated>; the event also fires without a restart, for other items and
      after a source replacement, and a script cannot tell these apart, so a call of an earlier activation may still
      be running) and again after the delay below, before any new work: for each record in processes.list(), go
      through record.recovery(Clock.System.now(), settleAfter = lease) (import kotlin.time.Clock). The lease must be longer than the longest
      time one attempt or external call of this harness can run without being recorded, retries included, so give
      every call a timeout. Attempts and writes changed less than the lease ago are not listed: a call may still be
      running, and the external system cannot tell you yet whether it will happen. Never conclude that a write did
      not happen before the lease has passed; run the reconciliation again after it (scheduler.at) when something
      was still young. Entries: ProcessRecovery.AttemptOpen (ask the executor's owner: a workflow run that
      still runs stays in flight and is finished with its own outcome, a finished one is closed with it, only a
      lost executor or one that cannot outlive a restart is closed with Finish(attempt.number,
      AttemptOutcome.Failed(AttemptFailure.Interrupted))), ProcessRecovery.WriteOpen (MarkWriteUnknown(key,
      write.tries), look at the external system itself, then ConfirmWrite or WriteNotApplied(key, write.tries)) and
      ProcessRecovery.WaitOpen (check the state the wait was about, an event may have been missed, isDue says a
      deadline passed, then resume or keep waiting). Prefer processes.reconcileWait(id) { record, wait ->
      /* query actual external state */ false } from Activated and event/timer handlers. It reads the durable wait
      once, checks the condition outside the transaction, and applies ResumeChecked with that snapshot only when
      satisfied. A concurrent input revision or SHA update gives Rejected(StaleRevision); reconcile again. A late
      answer cannot resume a newer wait or reopened process. Persist observed revision/SHA before reconciliation;
      the local comparison cannot discover remote edits by itself. Waiting uses no workflow or live agent; each
      resumed stage starts a new keyed workflow with its own deadline. A kept attempt or wait stays listed, so never
      loop until the list is empty. With harness.durable_processes enabled (default false), new records pin
      ProcessDefinition(item, revision) from the approved activation. Existing pins remain enforced when it is off.
      After source replacement, reads and cleanup remain available, but starting/resuming a stage returns
      DefinitionMismatch until processes.apply(id, ProcessCommand.MigrateDefinition(record.definition,
      ProcessDefinition(item, revision))) explicitly accepts the current code. Migrate only between stages with no
      unresolved external writes. Legacy records have definition=null and require explicit acceptance too. Migration
      keeps attempts, waits and workflow structural keys; unknown schemaVersion fails closed without overwriting.
      In durable mode (or for existing pins), remove(id) archives a settled ended process before deleting its active
      record. archive(id) confirms a snapshot without removing it; archived(after, limit, process) searches this
      harness's archive, readArchive(ref) resolves it, restore(ref) returns the same logical process without rewinding
      newer active state or changing workflow keys. A different active lifetime conflicts. Restore never starts
      execution; reconcile open work and migrate changed logic explicitly. Native cleanup receipts survive workflow
      retention. A full active quota reclaims only eligible completed records after confirmed archival; a full archive
      fails without deleting originals. Deleting a harness preserves its processes before removing the namespace;
      the profile ProcessArchiveReader still resolves their refs. Public results stay with their external provider,
      private record snapshots stay protected in this profile. Never log ids, results or journal
      entries.
    - workflows.start(process.workflowKey(StageName("verify")), ItemName("verify"), input) reserves the process
      attempt atomically before dispatch and returns the same RunId on an exact retry. Keep this key; a fresh
      next-attempt key means new work. The key includes a process lifetime, so remove/open cannot reuse old keys.
      Changed input, harness revision, script or project conflicts. After a crash before journalling, retry with
      the original snapshot; unavailable state never proves the old executor is absent. workflows.get(key) returns
      ScriptWorkflowState.Missing / Unavailable / Found(run, status, isCancellationRequested). Only the owning
      approved script can read or cancel its run. workflows.cancel(key) returns Requested / Finished / Missing /
      Unavailable: Requested still requires observation until native helpers and organisms have released resources.
      Running and unclosed process attempts protect their journals from result retention; after Finish the normal
      retention applies and a retired attempt cannot start again. Workflow completion is evidence, not business
      success: the harness decides the matching ProcessCommand.Finish outcome. The old start(name, input) remains
      available and intentionally starts a fresh run on each call. Keyed operations are forbidden from hooks.
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
    Hooks cannot send, wake, spawn, run commands or start workflows. Not available: top-level suspend (use
    script.scope.launch), @Serializable, println, exitProcess, global coroutine scopes, blocking bridges and @file
    annotations.
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
    - OrganismExecution(baseCommit = null, maxCells = 16, maxDepth = 4, maxCases = 24,
      maxOpenCasesPerCell = 2) requests an isolated stage. Limits can only be reduced; baseCommit must be a full
      lowercase 40/64-digit Git commit. Null captures committed project HEAD before conception. Choose a previous
      stage's Answered.output.commit explicitly to start from its result. Combining multiple cell branches is an
      explicit agent Git operation inside the zygote sandbox; no merge or publication is automatic.
      Requires the host's harness.isolated_organisms context captured at workflow launch. The host selects the full
      caller harness set plus the owner, never IDs from workflow JSON. Parentless scripts carry their owner harness.
      Every cell uses a pinned physical sandbox and the existing run_build snapshot queue. Missing sandbox support
      refuses conception with no Shared fallback. Legacy runs without context keep their old contract; an explicit
      execution request without context is refused. Context, growth options and base cannot change during replay.
      After all native cells stop, Answered.output holds a host-verified branch/base/commit proof. Failed seal,
      missing or substituted proof produces Ended(Failed); Answered still does not establish business success.
    - organism(goal, OrganismOptions(title, target = null, immunityTarget = null, execution = null)): OrganismOutcome — runs one
      organism of organic AI (a zygote session that divides into cells, with an immune system) as the owned
      executor of a large stage. Handle the outcome with when: Answered(text) — the zygote answered after every
      cell ended; that is not proof the stage reached its goal, so check the goal yourself in a following step;
      Refused(Unavailable | Rejected) — nothing was started (organic AI is off or asleep, or it did not accept the
      goal); Ended(NoModel | Failed | Aborted) — the organism never answered. target pins the model of the cells
      (null — the user's default), immunityTarget the judges (null — target); trust is capped at Ask and cell
      permission requests are not shown to the user yet. The organism is conceived once per step: replay and
      restarts return the recorded outcome or reconcile with the same organism and never start another;
      changing the goal or options of a recorded step fails the run as Diverged. A run owns at most
      ${HarnessLimits.ORGANISMS_PER_RUN} organism at a time and the profile ${HarnessLimits.ORGANISMS_PER_PROFILE};
      waiting for a free slot is not a failure. The goal is at most ${HarnessLimits.ORGANISM_GOAL_CHARS} characters;
      an answer longer than ${HarnessLimits.RESULT_CHARS} fails the step as Error. Cancelling the run or the
      surrounding parallel branch stops the organism, and its slot returns only after organic AI shows that its
      work ended; pausing the run leaves it running;
    - parallel({ … }, { … }) — branches run concurrently;
    - step("name") { … JsonElement } — memoized local work; now() — memoized clock;
    - prompt(ItemName("t"), args) / skill(ItemName("s")) — pinned content of the harness.
    Runs survive restarts: finished steps replay from the journal, so keep the call order deterministic and put
    nondeterministic work inside step { }. A changed prompt for a recorded step fails the run as Diverged.
    The result is JSON, at most ${HarnessLimits.RESULT_CHARS} characters.
""".trimIndent()

private val EVENTS = """
    Events (package io.aequicor.heartbeat.feature.harness.api.event), subscribe with events.on<Type> { }:
    - SystemEvent.Started, SystemEvent.NetworkChanged(isConnected) — both only with the scheduler enabled.
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

/** Desktop script of a pull request map: a declared gh command behind a UI method, with dependencies and a diagram. */
internal val EXAMPLE_UI_SCRIPT = """
    import kotlinx.serialization.json.Json
    import kotlinx.serialization.json.contentOrNull
    import kotlinx.serialization.json.jsonArray
    import kotlinx.serialization.json.jsonObject
    import kotlinx.serialization.json.jsonPrimitive

    val prList = ItemName("pr_list")
    val fields = "number,title,url,headRefName,baseRefName"

    commands.define(
        prList,
        ScriptCommand(
            "gh",
            listOf("pr", "list", "--repo", "{repo}", "--limit", "10", "--json", fields),
            parameters = mapOf("repo" to Regex("\\w[\\w.-]*/[\\w.-]+")),
            timeout = 30.seconds,
        ),
    )

    fun field(pr: JsonObject, name: String): String = pr[name]?.jsonPrimitive?.content.orEmpty()

    fun refusal(reason: ScriptCommandRefusal): String = when (reason) {
        ScriptCommandRefusal.InvalidParameter -> "Enter the repository as owner/name"
        ScriptCommandRefusal.NoProject, ScriptCommandRefusal.OutsideScope -> "Open this app in a project chat"
        ScriptCommandRefusal.Busy -> "A request is already running"
        else -> "Commands are unavailable here"
    }

    fun pullRequests(output: String): ScriptUiResult {
        val invalidOutput = ScriptUiResult.Error("gh output was incomplete or invalid; request fewer pull requests")
        // The runner's omission marker may land inside a JSON string and still parse as a partial list.
        if (Regex("\\n… [0-9]+ bytes omitted …\\n").containsMatchIn(output)) return invalidOutput
        val prs = try {
            Json.parseToJsonElement(output).jsonArray.map { it.jsonObject }
        } catch (_: IllegalArgumentException) {
            // Bounded command output may be incomplete; never include it in errors or logs.
            return invalidOutput
        }
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

    fun shown(result: ScriptCommandResult): ScriptUiResult = when (result) {
        is ScriptCommandResult.Exited -> if (result.exitCode == 0) {
            pullRequests(result.output)
        } else {
            ScriptUiResult.Error("gh failed; run: gh auth login")
        }
        is ScriptCommandResult.TimedOut -> ScriptUiResult.Error("gh did not answer in time")
        is ScriptCommandResult.Refused -> ScriptUiResult.Error(refusal(result.reason))
    }

    ui.method(ItemName("prs"), "Up to 10 open PRs of {repo: owner/name} with dependencies and a PlantUML map") { call ->
        val repo = (call.arguments["repo"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        shown(commands.run(prList, call.workspace, mapOf("repo" to repo)))
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

/** Desktop workflow with one organism as a large stage; every outcome is handled and the business goal is checked. */
internal val EXAMPLE_ORGANISM_WORKFLOW = """
    workflow { input ->
        val goal = (input["goal"] as? JsonPrimitive)?.content ?: "Prepare the release notes"
        when (val outcome = organism(goal, OrganismOptions("Release notes"))) {
            is OrganismOutcome.Answered -> {
                // An answer only says the zygote finished: judge the result against the goal in a separate step.
                val brief = "Check this result against the goal:\n" + outcome.text
                val review = agent(brief, AgentOptions("Check notes"))
                buildJsonObject {
                    put("status", "answered")
                    put("review", review)
                }
            }

            is OrganismOutcome.Refused -> buildJsonObject {
                put("status", "refused")
                put("reason", outcome.reason.name)
            }

            is OrganismOutcome.Ended -> buildJsonObject {
                put("status", "ended")
                put("reason", outcome.reason.name)
            }
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

    Organism stage: a workflow can hand one large stage to an organism of organic AI (needs organic AI on in the
    profile). Save it as a workflow item; every outcome is handled, and a separate step judges the business goal,
    because Answered only says the zygote stopped answering:

${EXAMPLE_ORGANISM_WORKFLOW.prependIndent("    ")}

    Mini-app with commands: only when the task actually needs a local program (here gh, already installed and
    authenticated). The command is declared in the script, so the script's safety review covers it; afterwards a
    button runs it without another agent turn or confirmation, also while no agent is available. It is not a
    prerequisite or a setup step for local mini-apps. Harness pr_map gets script "methods", then a surface built
    with ui_put calls it from a button to show up to 10 open PRs
    (a web card: heartbeat.call("prs", {repo: "owner/name"})):

${EXAMPLE_UI_SCRIPT.prependIndent("    ")}
""".trimIndent()
