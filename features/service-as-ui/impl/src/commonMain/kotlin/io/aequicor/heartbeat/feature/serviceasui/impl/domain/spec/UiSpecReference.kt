package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits

/** Icon names a button may use; the screen draws each as an icon button named by the button's label. */
internal val UiIconNames: List<String> = listOf(
    "play", "pause", "stop", "refresh", "copy", "link", "external", "open", "folder", "file", "code", "terminal",
    "calendar", "search", "filter", "sort", "edit", "trash", "download", "upload", "share", "send", "settings",
    "info", "warning", "success", "error", "check", "close", "plus", "minus", "pull-request", "merge", "branch",
    "commit", "globe", "database", "cloud", "user", "users", "mail", "bell", "star", "heart", "sparkles", "plan",
    "history", "bookmark", "tag", "pin", "lock", "eye", "chat", "grid",
)

/**
 * The reference of the spec format for the agent (`ui_reference`): places, document, bindings, nodes, actions,
 * limits and the workflow with harness methods. [methodResultBytes] and [callSeconds] come from the data layer that
 * enforces them.
 */
internal fun uiSpecReference(methodResultBytes: Int, callSeconds: Int): String = listOf(
    placesAndDocument(),
    nodesAndActions(),
    limitsAndCards(methodResultBytes, callSeconds),
    miniAppsAndWorkflow(),
)
    .joinToString("\n\n")

private fun placesAndDocument(): String = """
# Heartbeat interface spec, version $UI_SPEC_VERSION

A surface is a JSON document that Heartbeat draws with its own components. It belongs to an owner, a harness whose
script publishes the methods the surface calls (`ui.method` in the harness script API). The user presses its buttons
without you: nothing runs except the owner's methods, with the owner's permissions.
Creating, replacing, removing and showing surfaces, and agent method calls, pass automatic safety review.
Safe proposals execute without a user permission dialog. Risk, uncertainty or unavailable review returns an error
to you; inspect the reason and correct the operation. Do not ask the user to bypass a safety refusal.

## Places (`placement`)
- `card`: the canonical mini-app. `ui_show(surface: id)` adds one compact entry to the chat; its Open button
  and the native composer chip open a spacious modal. The selected harness also shows the full app on new-chat start.
- `composer`: inline in a compact row above the chat input while the owner is connected to the chat.
- `start`: inline in the start window of a new chat for which the owner is selected.
- `panel`: in the side panel, opened by a launcher or by an `open` action with `presentation: "panel"`.
- `modal`: in a dialog, opened by a launcher or by an `open` action.
`launchers` (panel and modal surfaces only): `["composer", "start"]` put a button that opens the surface there.

## Document
{"version": $UI_SPEC_VERSION, "title"?, "state"?: {key: JSON}, "data"?: {name: {"method", "args"?, "load"?: "auto" | "manual"}},
 "modals"?: {name: {"title", "body": node, "wide"?: bool}}, "body": node}
- `state`: initial local values. Inputs write them by `bind`, as do `set` and `call.into`; every written key must be
  declared here.
- `data`: owner methods whose results fill `data.<name>`. `auto` sources load whenever a live library surface opens,
  including reply cards, and refresh after durable owner data changes. Use read-only methods for these sources;
  writing inside a source would trigger another refresh. Manual sources run only through `reload` actions.
  `status.<name>` tells `loading`, `failed`, `ready` and `error`. Local `state` is ephemeral; durable records belong
  in harness `data`, never only in a script variable, local state, HTML or browser localStorage.
Unknown fields and types are errors: the format is strict.

## Bindings
Strings may contain `{{path}}`. A string that is exactly one binding keeps the JSON type of the value (numbers,
arrays, objects): use that for list and table rows, arguments and conditions. Write a literal `{{` as `\{{`. There are
no expressions: compute values in the owner's methods.
Roots: `data.<source>…`, `state.<key>…`, `item` and `index` (inside list items and table rows), `args…` (arguments the
surface or modal was opened with), `status.<source>.loading|failed|ready|error`, `context.platform`
(`desktop`, `android` or `ios`), `context.hasSession`. Paths: `a.b`, `a[0]`.
Conditions (`if.when`, `enabledWhen`) are false for false, null, 0, "" and missing values.
    """.trimIndent()

private fun nodesAndActions(): String = """
## Nodes (`type`)
- Layout: `column` {children, gap?}, `row` {children, gap?, wrap?}, `card` {children, title?},
  `section` {title, children, description?}, `divider`, `spacer` {size?}. gap and size: none | small | medium | large.
  In a row, buttons, badges and spacers keep their width; other nodes share the rest.
  `gap` belongs only to `column` and `row`, never to the document, `card` or `section`. To space a card's contents,
  put a `column` with `gap` inside its `children`.
- Content: `text` {text, style?: body | caption | title | heading | code, tone?}, `markdown` {text},
  `badge` {text, tone?: neutral | brand | success | warning | danger}, `progress` {value? (0..1, absent:
  indeterminate), label?}, `keyValue` {items: [{key, value}]}, `empty` {title, description?},
  `diagram` {source (PlantUML without ``` fences), title?}.
- Collections: `list` {items (binding to an array), item: node, key? (field of each element that identifies it),
  empty?: node}; `table` {rows (binding to an array), columns: [{title, value (template of `item`), weight?}], key?,
  onRowClick?: action, empty?: text}. At most ${UiSpecLimits.LIST_ITEMS} elements are drawn; the rest are counted.
- Condition: `if` {when (binding), then: node, else?: node}.
- Input: `button` {label (always required: it names the button), action, style?: primary | secondary | ghost | danger,
  icon?, enabledWhen? (binding), confirm? (question asked first), id?}; `textField` {bind, label?, placeholder?,
  multiline?}; `toggle` {bind, label}; `select` {bind, options: [{value, label}], label?}; `native` {widget: date |
  file | folder, bind, label?, extensions?}: a date is written as YYYY-MM-DD, a file or folder as an absolute path
  (desktop only).
- `web` {html, bridge?, data?, height?: compact | regular | tall | content}: an isolated page in library surfaces (including
  `card` and `composer`) and in modals, one per surface, desktop only while web documents are enabled. Anonymous
  cards may show it only inside modals and never get a bridge; editor previews never execute owner methods.
  Saving a web surface or showing an anonymous web spec uses automatic safety review. `height` is a bounded viewport:
  compact for a standalone status strip; regular (default) and tall are fixed presets. Content grows and shrinks to
  its body or `[data-heartbeat-root]` within host bounds. The full mini-app fills the start view; its modal follows
  content height up to the available screen height and shrinks again when content becomes shorter. These app views
  ignore the preset, with one page scroll and no outer chat scroll. Keep the page responsive and
  its content in normal flow; avoid fixed pixel page heights and nested overflow containers.
  A mini-app modal whose root is `web` has no host padding or title bar; close overlays the top-end corner.
  `--hb-overlay-width` and `--hb-overlay-height` describe that occupied area in CSS pixels (zero elsewhere).
  Let your header or toolbar account for this area at its inline end without adding an empty full-width top strip.
  The page has its own origin and no network: inline scripts and styles, `data:` images and fonts; no fetch, sockets, WebRTC, frames, workers, forms or links away. It reads `data` (a binding) as
  `heartbeat.data` and later values from the `heartbeat:data` event on `window` (`event.detail`). With `bridge: true`
  (library surfaces), `await heartbeat.call(method, args)` calls the owner's method with object `args` and returns
  its value, or rejects with an `Error` of the owner's message.
  The host supplies semantic CSS variables, a default system-sans body and inherited form fonts. Use
  `--hb-surface`, `--hb-surface-raised`, `--hb-text`, `--hb-text-muted`, `--hb-border`, `--hb-accent`,
  `--hb-on-accent`, `--hb-accent-muted`, `--hb-control`, `--hb-input`, `--hb-focus`, `--hb-danger`,
  `--hb-on-danger`, `--hb-success`, `--hb-on-success`; typography: `--hb-font-family`, `--hb-font-size`,
  `--hb-line-height`, `--hb-body-size`, `--hb-body-line-height`, `--hb-title-size`, `--hb-caption-size`;
  spacing: `--hb-space-xs`, `--hb-space-s`, `--hb-space-m`, `--hb-space-l`, `--hb-space-xl`, `--hb-control-height`.
  `heartbeat.theme` has `colorScheme` (light/dark) and `variables`; `heartbeat:theme` announces changes. CSS variables
  update without reloading the page, changing `heartbeat.data`, or granting method access. Follow host colors;
  do not infer the application theme only from prefers-color-scheme or hardcode a dark background.
  Native JavaScript dialogs (`alert`, `confirm`, `prompt`) and pop-ups are blocked. Show feedback inside the page;
  call methods directly for ordinary actions. Never gate a working button on `window.confirm`.
Icons draw an icon button whose label is its tooltip and accessible name: ${UiIconNames.joinToString(", ")}.

## Actions (`type`)
`call` {method, args?, into? (state key for the result)}, `reload` {source}, `set` {key, value},
`focusCard` {surface: owner-local name} (opens the canonical app of this surface, or focuses its chat card as a fallback;
unavailable to anonymous cards and editor previews),
`open` {modal? | surface? (another surface of the same owner, by name), presentation?: modal | panel, args?},
`close` (closes the modal it runs in), `prompt` {text, submit?} (surfaces of the library may send the prompt; cards
only put it into the chat input), `openUrl` {url: http or https}, `copy` {text}, `saveFile` {text, name?,
extensions?}, `reveal` {path: absolute; library surfaces only}, `sequence` {actions} (stops at the first failure).
    """.trimIndent()

private fun limitsAndCards(methodResultBytes: Int, callSeconds: Int): String = """
## Limits
${UiSpecLimits.NODES} nodes, nesting ${UiSpecLimits.DEPTH}, ${UiSpecLimits.SOURCES} data sources,
${UiSpecLimits.MODALS} modals, ${UiSpecLimits.SEQUENCE} steps in a sequence, ${UiSpecLimits.COLUMNS} table columns,
${UiSpecLimits.OPTIONS} select options, ${UiSpecLimits.STATE_BYTES / KIB} KiB of initial state,
${ServiceUiLimits.SPEC_BYTES / KIB} KiB per spec, ${UiSpecLimits.HTML_BYTES / KIB} KiB of HTML,
${methodResultBytes / KIB} KiB per method result, $callSeconds s per call, ${UiSpecLimits.CALLS} calls at a time per
surface; ${ServiceUiLimits.SURFACES} surfaces per profile, ${ServiceUiLimits.SURFACES_PER_OWNER} per owner.

Each drawn body or modal shares ${UiSpecLimits.RENDER_ELEMENTS} expanded elements (including rows, cells, pairs and
options) and ${UiSpecLimits.RENDER_CHARS} formatted display characters: excess elements are omitted and text is truncated.
Input values stay intact.
List and table hidden counts include rows omitted by this shared budget. Item keys beyond ${UiSpecLimits.ITEM_KEY_CHARS}
characters fall back to positions.

## Cards (`ui_show`)
A card shows a surface in your reply. An inline card has no owner: no `call`, `reload`, data sources, `reveal`,
sent prompts or web bridge; the user confirms its links. A card of a library surface may call the owner's methods
when the user presses its buttons or its web page calls the bridge. The full application belongs to its chat pane,
not to the transcript row: hiding, reopening and moving it between start and modal keep the same document and drafts.
Turning the owner off, switching to another context or replacing its spec revokes the previous execution.

    """.trimIndent()

private fun miniAppsAndWorkflow(): String = """
## Mini-apps inside the conversation
Build a full app as one owner-backed `card` surface with internal tabs, forms and history. The host supplies its
compact chat entry, composer button, full start view and modal. Do not create duplicate `composer` or `start`
surfaces for that app; existing mirrors from the same owner are hidden. Keep the chat, sidebar and input intact.
Prefer native nodes for straightforward lists, forms and status. Use `web` with inline HTML/CSS/JS when the task
needs a custom view, with host theme variables. Keep styles inside the document; do not create another
sidebar or open a panel for this workflow. Declare `data` auto sources, bind `web.data` to `{{data.snapshot}}`, render
`heartbeat.data` on startup and listen for `heartbeat:data` updates. Handle missing data and rejected bridge calls.
Buttons call approved harness `ui.method` methods; persist changed values using harness `data` before returning.
Resume controls must keep existing records. Check the current state inside `data.update` before starting a new
session so repeated clicks cannot reset active progress; separate starting, resuming and clearing actions.
The host updates other drawn surfaces after a durable write, without a model turn or a page reload. Do not poll or
read the same data after every successful call. Use inline SVG or CSS for artwork; isolated pages have no network.

## Embedded app design
- Preserve the chat shell. One concise app header, a useful current state and one primary action; secondary actions
  stay quiet. Use compact internal tabs only when they organize real content. No second sidebar or navigation shell.
- Use the host's proportional sans font for labels, buttons, prose and numbers; monospace is for source code only.
  Use tabular figures for aligned numeric columns. Hierarchy comes from 400/500/600 weights and spacing, not all-caps.
- Honor a requested visual style through a restrained accent or small illustration. A space theme can use an orbit
  motif; it does not require neon glows, a starfield background, glowing pills, gradients or a terminal typeface.
  Flat rows, subtle dividers and calm selection states should carry the working interface.
- Show the next useful task first. Avoid hero-sized headings, decorative metrics, nested cards and empty dashboard
  space. Empty history should explain that there are no records and offer the relevant action, without fake progress.
- Use width before adding height: align a row's title and summary on the left, related inputs and actions on the
  right. Keep tags and details on a compact secondary line; stack these groups at narrow widths.
- Keep headers, tabs and toolbars compact. Content determines row height: no empty spacers, stretched rows or
  vertical space-between layouts. Fit the usual worklist in view through layout and spacing, while preserving
  readable text and comfortable touch targets. Longer lists scroll naturally; never clip records to make them fit.
- Collapse multi-column layouts at narrow widths; no horizontal page scroll. Buttons have visible labels,
  :focus-visible states and sufficient contrast. Do not communicate state only by color; respect reduced motion.
- Use the available viewport, with one page scroll. Loading, empty, success and error states must fit naturally.
  Render one visible app heading; the native host does not repeat web-document headings. Preserve durable records
  when redesigning. Update the existing surface rather than adding another card.

## Workflow
1. Publish the methods in a harness script item: `ui.method(ItemName("prs"), "Open pull requests") { call ->
   ScriptUiResult.Value(json) }`; report expected problems with `ScriptUiResult.Error(message)`.
   Every ItemName must match `[a-z][a-z0-9_]{0,31}`: use `log_set`, never `logSet`.
   For an early return from that lambda use `return@method`, never `return@ui.method`; or return an if/else expression.
2. `ui_list` shows owners and their methods; `ui_call` checks what a method returns.
3. `ui_put` creates or replaces the surface and returns diagnostics with JSON paths to fix.
4. For an embedded mini-app use `placement: "card"`, then `ui_show` with the returned surface id. A saved surface
   is also shown on new-chat start when its owner is selected. The host creates the compact composer button.
   To fix an already shown app, replace its existing surface with `ui_put`: live cards update automatically.
   Repeating `ui_show` updates or switches the conversation's single live app slot and its arguments;
   it never creates another copy.
    
    """.trimIndent()

private const val KIB = 1024
