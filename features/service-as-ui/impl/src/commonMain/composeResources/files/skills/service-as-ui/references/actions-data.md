# Actions, data and tools

Load with `ui_reference(topic="service-as-ui/actions-data")`.

## Bindings and local state

Use `{{path}}` in formatted strings. A string containing exactly one binding retains the underlying JSON type; `"{{state.count}}"` can therefore pass a number, while `"Count: {{state.count}}"` produces text. No arithmetic, equality tests, function calls, filtering or JavaScript expressions are supported. Calculate derived values in the owner.

| Root | Meaning |
|---|---|
| `data.snapshot` | Result of a declared source named `snapshot`, including nested objects and arrays |
| `state.note` | A declared local value; inputs, `set` and `call.into` may write it |
| `item`, `index` | Current list item/table row and its index; unavailable outside that scope |
| `args.id` | Arguments supplied when showing/opening this surface or opening a modal |
| `status.snapshot.loading` / `.failed` / `.ready` / `.error` | Source request state and error text |
| `context.platform` / `context.hasSession` | Host platform (`desktop`, `android`, `ios`) and whether a chat session exists |

Paths support fields and array offsets: `data.snapshot.days[0].label`. Conditions are false for false, null, zero, an empty string, empty array/object and a missing value. Escape a literal `{{` as `\{{` in the string value (write `\\{{` in JSON). Declare every writable key in initial `state`; `bind`, `set.key` and `call.into` use the bare key, such as `note`, not `state.note` and not a binding.

Use local state for drafts and view choices. Store meaningful records, timers, settings and operation identities in owner data. Neither local state nor WebView storage is a durable database.

Source reloads update `data` and `status`, leaving native `state` values intact. A conditional view can recreate its controls while retaining their declared state values; do not assume it also retains keyboard focus. The canonical app retains its surface state while hidden/reopened in the same context. Replacing the spec, changing owner/context or restarting the application can initialize it again. Persist resumable drafts through an owner method when that is part of the task.

## Sources and owner methods

A source has `method`, optional object `args`, and `load:"auto"|"manual"` (default `auto`). Its result appears at `data.<source>`. Auto sources run when a live library surface opens and after durable owner-data changes. They must be read-only: writing during a refresh can cause a refresh loop. Manual sources run through `reload`.

Buttons call published owner methods without invoking the model. Harness methods are registered with `ui.method`; discover the available method names and descriptions through `ui_list`. Read the harness API reference before creating or changing their implementation. Harness item/method names use `[a-z][a-z0-9_]{0,31}`, for example `save_note`, not `saveNote`.

### Offline apps and commands

Owner methods run without a model turn, so the app keeps working while no agent is available: no engine connection, failed engine or offline network. When a button should run a local program (build, tests, a linter, a read-only `git`/`gh` query), declare it in the harness script with `commands.define` and call it from a method with `commands.run`; do not make the button a `prompt` that asks the agent to run it. The script's safety review covers the declared commands once. Later presses of native buttons and WebView `heartbeat.call` run them without another review, agent turn or confirmation, so declare only commands safe to repeat unattended and pass user input only as declared parameters. Commands run in the chat's project on Desktop and require the `harness.commands` setting; return `ScriptUiResult.Error` with a clear message for a refusal, a timeout or a non-zero exit. Reserve `prompt` actions for work that needs the model.

A mutation should validate input and current state, save atomically, and then return a useful result. A validation error must leave existing data intact. Read-modify-write operations must recheck inside the atomic update; repeated starts must not reset an active session. For retryable record creation, choose a stable operation ID and deduplicate in the owner. A UI timeout alone does not prove that a write failed.

Use only the actual method's arguments and return shape. A missing undo/edit or deduplication method is a service-contract gap: implement it within the authorized task, or state the limitation. Do not invent callable methods or extra arguments. A snapshot flag cannot validate a draft that was never sent to that method.

Use returned mutation state or the host's subsequent snapshot update to render the result. Do not poll the same owner after every successful write. Live surfaces refresh their auto sources after durable writes; stale responses must not become a reason to overwrite newer user input. Preserve unrelated draft values while updating saved data.

## Complete action catalog

These are action fragments for a button's `action`, a table's `onRowClick`, or a `sequence` step. Example keys, methods, sources and surfaces must exist in the containing app.

| Action | Fields and scope | Example |
|---|---|---|
| `call` | `method`, optional object `args`, optional declared state key `into`; owner-backed only | `{"type":"call","method":"save_note","args":{"text":"{{state.note}}"},"into":"result"}` |
| `reload` | `source`; reruns the named source, including manual ones | `{"type":"reload","source":"snapshot"}` |
| `set` | `key`, JSON `value`; local state only | `{"type":"set","key":"details","value":true}` |
| `focusCard` | `surface` as an owner-local name; opens the canonical app, with chat-card focus as fallback; unavailable to anonymous cards/previews | `{"type":"focusCard","surface":"journal"}` |
| `open` | Exactly one of local `modal` or a host-supported same-owner `surface`; optional `args`, `presentation:modal|panel` (default modal); see navigation scope below | `{"type":"open","modal":"details","args":{"record":"{{item}}"}}` |
| `close` | Closes the top named local modal opened by `open.modal`; does not close the host's app or placement dialog | `{"type":"close"}` |
| `prompt` | `text`, optional `submit:false`; inserts into chat input, or submits when supported by the live owner-backed host; needs an available agent — run local programs through owner methods instead | `{"type":"prompt","text":"Help me plan this week.","submit":false}` |
| `openUrl` | `url`, HTTP or HTTPS only; uses the host browser | `{"type":"openUrl","url":"https://example.com/help"}` |
| `copy` | `text`; copies the evaluated text | `{"type":"copy","text":"{{data.snapshot.summary}}"}` |
| `saveFile` | `text`, optional `name` (default `export.txt`), `extensions`; user selects destination, Desktop-only | `{"type":"saveFile","text":"{{data.snapshot.csv}}","name":"journal.csv","extensions":["csv"]}` |
| `reveal` | Absolute `path`, owner-backed library only, Desktop-only; shows an existing path in the file manager | `{"type":"reveal","path":"{{state.path}}"}` |
| `sequence` | Nonempty `actions` array; runs in order, stops at first failure; not a transaction/rollback | `{"type":"sequence","actions":[{"type":"call","method":"save_note","args":{"text":"{{state.note}}"}},{"type":"set","key":"details","value":true}]}` |

### Quick actions

A canonical `card` app of a library owner may declare `quickActions`: contextual buttons the host draws next to the chat input (with an overflow menu when they do not fit), so the user starts or continues a stage of a process without typing a service prompt. They are a top-level spec field, absent from specs that declare none.

| Field | Meaning |
|---|---|
| `id` | Unique identifier (`[A-Za-z_][A-Za-z0-9_-]{0,31}`); names the button in logs and in its busy state |
| `label` | Required visible text; it names the button for screen readers and in the overflow menu. May hold bindings |
| `action` | `call`, `reload` or `set`, alone or in a `sequence` |
| `style`, `icon` | As for a `button` |
| `visibleWhen` | Binding; the action is hidden while it is false |
| `enabledWhen` | Binding; the action is shown disabled while it is false |
| `confirm` | Question the user answers first |

Bindings read `data`, `state`, `status`, `args` and `context`, never `item` or `index`. At most 8 quick actions. `prompt`, `open`, `focusCard`, `openUrl`, `copy`, `saveFile`, `reveal` and `close` are rejected: a quick action never opens the app's screen and never replaces the draft in the chat input. Put stage logic (start, continue, retry) in owner methods and let the action call them; the method name and arguments are checked against the owner's published methods like any other `call`. Anonymous cards and surfaces with another placement cannot declare them.

```json
"quickActions": [
  {"id": "next", "label": "Continue {{data.snapshot.stage}}", "style": "primary", "icon": "play",
   "visibleWhen": "{{data.snapshot.hasNext}}", "enabledWhen": "{{status.snapshot.ready}}",
   "action": {"type": "call", "method": "advance", "args": {"stage": "{{data.snapshot.stage}}"}}}
]
```

### Navigation scope

`args` must be a JSON object. A local named modal cannot be presented as a panel. Use named local dialogs for details within a canonical app; a same-owner name alone does not make every saved surface a navigation target.

| Request | Supported host and target |
|---|---|
| `open.modal` | Opens a dialog declared in this spec's `modals`, sharing its state and data. Works within the canonical app and other rendered surfaces. |
| `open.surface`, default/modal presentation | From a placement host (`composer`, `start`, `context`, `panel` or `modal`), opens an enabled same-owner `panel` or `modal` surface whose owner is active. A canonical app can only reopen itself with unchanged opening arguments; it cannot open a different saved surface as a modal. Transcript card hosts do not open saved surfaces this way. |
| `open.surface`, panel presentation | Opens an enabled same-owner `panel` or `modal` surface through a host dock. Placement hosts without a dock instead open an eligible target as a dialog. Canonical-app and transcript-card hosts require dock support. |
| `focusCard` | Opens the owner's canonical app when supported, with transcript-card focus as fallback. It does not navigate to an arbitrary sibling app from inside the canonical app. |

For example, a context surface can use `{"type":"open","surface":"inspector","presentation":"panel","args":{"id":"{{item.id}}"}}` when inside an item template and an eligible `inspector` exists. On a host without a dock it opens as a dialog. If the user prohibits side panels, use a local modal or a modal launcher instead of relying on the panel request's fallback.

Native buttons become busy/disabled and show activity while their action runs; clickable table rows also become busy. The host displays owner errors or execution failures as a surface notice. These affordances do not replace owner-side idempotency. There is no per-call `status` binding: `status.*` belongs to data sources. Do not simulate pending state with `set busy=true → call → set busy=false`, because a failed call stops that sequence before cleanup.

A `call` succeeds when the owner returns a successful method result, and only then writes `into` or continues a sequence. A harness should signal rejected persistence with `ScriptUiResult.Error(message)`. Returning `ScriptUiResult.Value({"error":"..."})` or a value containing `ok:false` is still a successful call to the host; it does not stop a sequence. Verify the method's error contract before using follow-up navigation as proof of success. Avoid clearing a draft in a sequence when the user can keep editing during the call; an explicit new-entry action preserves those newer edits.

Transcript cards only insert prompts into the input, even when backed by a library surface; they do not submit a model turn. A full live owner-backed app/slot can submit if its current host allows it. Anonymous specs cannot request `submit:true`. Use explicit user-facing action wording when a button submits a prompt.

`openUrl` from an anonymous card asks the user before following a link. WebView links cannot navigate away; a native host action is the appropriate external-link mechanism. Native file selection/save dialogs are part of the requested interaction, not an extra tool-approval flow.

## Complete tool catalog

| Tool | Arguments | Purpose |
|---|---|---|
| `ui_reference` | Optional `topic` | Read bundled design/authoring guidance or the exact installed spec. No owner/project required. Select a reference topic to avoid loading unrelated detail. |
| `ui_preview` | `{html,data?,width?,height?,theme?,fontScale?,includeScreenshot?}` | Check an isolated WebView draft before saving, with sample data and no owner bridge. Returns text diagnostics by default; request a PNG only on image-capable models. Desktop/web documents required; see `service-as-ui/webview`. |
| `ui_list` | `{}` | List library surfaces and owners, enabled status and published methods. `methods` can be unavailable when the owner's script is not running. |
| `ui_get` | `{id}` | Read a saved surface's full spec, metadata and current revision before editing. |
| `ui_put` | `{owner,name,placement,title,spec,description?,launchers?,expectedRevision?}` | Create or replace a surface; returns validation diagnostics and saved identity/revision. `spec` is a JSON object, not a JSON-encoded string. |
| `ui_delete` | `{id,expectedRevision}` | Remove a library surface at that exact revision; not a workout-data or owner-data deletion tool. |
| `ui_show` | `{title,surface?,spec?,args?}` | Use exactly one of saved `surface` ID or anonymous inline `spec`. Saved surfaces use the single live app slot; anonymous specs add independent snapshots. |
| `ui_call` | `{owner,method,args?}` | Execute a real owner method from the agent; useful for inspecting snapshots and authorized operation checks. |

Use the exact owner reference from `ui_list`, typically `harness:<id>`, or its unambiguous name. Surface names are stable lowercase slugs matching `[a-z][a-z0-9_]{0,31}`. Other actions use owner-local names; `ui_get`, `ui_delete` and `ui_show(surface)` use the full returned ID.

Creation uses omitted `expectedRevision` or `0` and fails if the surface already exists. Replacement/deletion uses the current revision. On a conflict, reread and reconcile the intended change instead of retrying with a guessed revision. `ui_put` replaces the full surface specification; retain unrelated functionality and metadata. Tool arguments do not include an enable/disable flag; the library's user interface exposes that control.

Library writes, showing surfaces and method calls use the shared profile security mode. Confirmation covers the exact operation. A binding or revision can expire; refresh the evidence and resubmit the corrected operation. Full Trust does not bypass ownership or revision checks.

## Anonymous cards and previews

Anonymous inline specs may render native content, local input/state, named modals, copy/save, links and draft prompts. They cannot use owner `call`, `reload`, data sources, `focusCard`, another owner's/library surface, `reveal`, sent prompts, or a WebView bridge. Their one WebView may appear only inside a named modal, not in the body. Editor previews never execute owner methods or bridges, even if an owner is selected.

## Current bounds

Use `ui_reference(topic="spec")` for the installed version's authoritative bounds if diagnostics differ.

| Area | Bound |
|---|---|
| Profile library | 64 surfaces; 16 per owner; 16 MiB combined specs, titles and descriptions |
| Surface metadata | Title 80 characters; description 500 characters; surface name 32 characters |
| Spec | 2 MiB UTF-8; 400 nodes; node nesting 16; raw JSON nesting 64 |
| Data/local state | 8 sources; 16 KiB initial state; opening arguments 16 KiB |
| Modals/actions | 8 named modals; 8 quick actions; 8 steps per sequence; 2 concurrent native action/source calls per surface |
| Collections | 12 table columns; 64 select options; 200 list items/table rows rendered before hidden counts |
| Expanded rendering | 4,096 elements and 262,144 display characters per body/modal; input values stay intact; keys above 128 characters fall back to positions |
| WebView | One document per spec; HTML 1 MiB; up to 4 pending bridge calls, executed one at a time; further calls are rejected |
| Owner call | Arguments 16 KiB for `ui_call`, native `call` and the WebView bridge; result 256 KiB; the installed tool reference advertises 60 seconds, with a 65-second provider guard; the owner's own budget may be shorter |
| Agent `ui_call` output | Returned text is truncated to 16,000 characters; a large JSON result may therefore be incomplete. Inspect a smaller snapshot or page instead. |
| Native exports/prompts | Text export up to 4 MiB on Desktop; inserted/submitted prompt up to 16,384 characters |

For longer histories return a page or meaningful subset from an owner method. A hidden-count indicator is not pagination or a reason to silently discard durable records.
