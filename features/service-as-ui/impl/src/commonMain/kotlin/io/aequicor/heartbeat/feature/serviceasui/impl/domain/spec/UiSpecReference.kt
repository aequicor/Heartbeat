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
internal fun uiSpecReference(methodResultBytes: Int, callSeconds: Int): String =
    listOf(placesAndDocument(), nodesAndActions(), limitsAndWorkflow(methodResultBytes, callSeconds))
        .joinToString("\n\n")

private fun placesAndDocument(): String = """
# Heartbeat interface spec, version $UI_SPEC_VERSION

A surface is a JSON document that Heartbeat draws with its own components. It belongs to an owner, a harness whose
script publishes the methods the surface calls (`ui.method` in the harness script API). The user presses its buttons
without you: nothing runs except the owner's methods, with the owner's permissions.

## Places (`placement`)
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
- `data`: owner methods whose results fill `data.<name>`. `auto` sources load when the surface opens in a slot or a
  panel; cards never load by themselves. `status.<name>` tells `loading`, `failed`, `ready` and `error`.
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
- `web` {html, bridge?, data?}: panel and modal surfaces only, one per surface, desktop only; saving it always asks
  the user.
Icons draw an icon button whose label is its tooltip and accessible name: ${UiIconNames.joinToString(", ")}.

## Actions (`type`)
`call` {method, args?, into? (state key for the result)}, `reload` {source}, `set` {key, value},
`open` {modal? | surface? (another surface of the same owner, by name), presentation?: modal | panel, args?},
`close` (closes the modal it runs in), `prompt` {text, submit?} (surfaces of the library may send the prompt; cards
only put it into the chat input), `openUrl` {url: http or https}, `copy` {text}, `saveFile` {text, name?,
extensions?}, `reveal` {path: absolute; library surfaces only}, `sequence` {actions} (stops at the first failure).
    """.trimIndent()

private fun limitsAndWorkflow(methodResultBytes: Int, callSeconds: Int): String = """
## Limits
${UiSpecLimits.NODES} nodes, nesting ${UiSpecLimits.DEPTH}, ${UiSpecLimits.SOURCES} data sources,
${UiSpecLimits.MODALS} modals, ${UiSpecLimits.SEQUENCE} steps in a sequence, ${UiSpecLimits.COLUMNS} table columns,
${UiSpecLimits.OPTIONS} select options, ${UiSpecLimits.STATE_BYTES / KIB} KiB of initial state,
${ServiceUiLimits.SPEC_BYTES / KIB} KiB per spec, ${UiSpecLimits.HTML_BYTES / KIB} KiB of HTML,
${methodResultBytes / KIB} KiB per method result, $callSeconds s per call, ${UiSpecLimits.CALLS} calls at a time per
surface; ${ServiceUiLimits.SURFACES} surfaces per profile, ${ServiceUiLimits.SURFACES_PER_OWNER} per owner.

## Cards (`ui_show`)
A card shows a surface in your reply. An inline card has no owner: no `call`, `reload`, data sources, `reveal`,
sent prompts or web bridge; the user confirms its links. A card of a library surface may call the owner's methods
when the user presses its buttons.

## Workflow
1. Publish the methods in a harness script item: `ui.method("prs", "Open pull requests") { call ->
   ScriptUiResult.Value(json) }`; report expected problems with `ScriptUiResult.Error(message)`.
2. `ui_list` shows owners and their methods; `ui_call` checks what a method returns.
3. `ui_put` creates or replaces the surface and returns diagnostics with JSON paths to fix.
    
    """.trimIndent()

private const val KIB = 1024
