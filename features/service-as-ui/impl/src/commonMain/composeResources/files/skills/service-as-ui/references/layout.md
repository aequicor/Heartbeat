# Layout, placements and platform capabilities

Load with `ui_reference(topic="service-as-ui/layout")`. These are the supported extension points and node types. Nodes inherit the host's typography, colors, spacing and control behavior; arbitrary CSS and Compose modifiers do not apply to native JSON nodes.

## Extension points

`placement` belongs to the `ui_put` envelope, not inside `spec`.

| Placement | Where and when it appears | Typical use |
|---|---|---|
| `card` | One canonical app: compact entry from `ui_show` in the chat; host composer button opens the app in a modal; the selected owner has a full app on new-chat start | Workout tracker, reading journal, issue dashboard |
| `composer` | Compact inline content above the input for the connected owner | A brief independent status or action relevant while writing |
| `start` | Inline content on new-chat start for the selected owner | A small owner-specific onboarding or initial choice |
| `context` | Floating top-right card for the active/selected owner: automatic beside chat when its context controls can stay on one row; otherwise Summary reveals it over chat | Current task summary, short list, quick actions |
| `panel` | A host side panel opened by a launcher or `open` with `presentation:"panel"` | A workspace needed alongside chat; use only if compatible with the user's requested layout |
| `modal` | A host dialog opened by a launcher or `open` | Focused settings, details, a substantial form |

Only `panel` and `modal` accept `launchers`, choosing any of `composer`, `start`, `context`. For example, `placement:"modal", launchers:["composer","start"]` exposes buttons in those two slots. A launcher opens the target surface; it is not another copy of its contents. A panel launcher uses the dock when available and a dialog otherwise; a modal launcher always opens a dialog.

The canonical `card` already supplies composer/start access. Same-owner composer/start mirrors are hidden when that app is present. A distinct context surface can coexist with it. To fit the context card beside chat, the host first shifts the transcript and input left without reducing their width, preserving the inset from the sidebar. If necessary, it then narrows them only while the context controls above the input still fit on one row. The card appears automatically when it fits under these constraints. If the pane is too narrow, it starts collapsed; Summary in the header shows it over chat without narrowing the input. Explicit show/hide choices survive resizing until the chat, project, harness or content availability changes.

The card remains a floating surface and does not open a dock panel, dim the background or automatically capture focus. Its height follows content within the space above the composer; narrow panes constrain its width. Context remains a separate slot from `panel` and `modal` placements and needs no surface launcher. Its spec supplies the content heading, so do not repeat it in a second outer card. Multiple context surfaces share the floating card with a selector.

On new-chat Start with a full canonical mini-app, the summary starts collapsed. Showing it puts the card at the top right in a band matching its content height, with the app below it, so the summary does not overlap a native WebView. The composer keeps its width and position. Escape collapses the summary without making it modal.

Use `focusCard` to reopen the owner's canonical app. Use `open.modal` for a named local dialog within an app. Opening a separate same-owner surface depends on the current host and the target's placement; see the navigation table in `service-as-ui/actions-data`. Neither operation launches a separate operating-system application window. A surface cannot open a different owner's surface by name.

## Document fields

Required: `version:1`, `body` (one root node). Optional: `title`, `state` (initial local JSON values), `data` (named owner sources), `modals` (named local dialogs).

Each modal has required `title` and `body`, plus optional `wide:true`. It shares the surface's state/data and can receive `args` from `open`. All node and action fields are strict: unknown fields fail validation. Spacing properties belong to the node types listed below, not to the document.

## Native node catalog

The examples in this table are node fragments, not standalone specs. Binding examples assume corresponding `state` keys and `data` sources have been declared; `item` is available only in a list item/table row.

| Type | Fields and behavior | Example fragment |
|---|---|---|
| `column` | `children`; `gap` defaults to `medium` | `{"type":"column","gap":"small","children":[]}` |
| `row` | `children`, `gap`, `wrap:false`; in a nonwrapping row, buttons, badges and spacers keep their width, other children share remaining width | `{"type":"row","wrap":true,"children":[]}` |
| `card` | `children`, optional `title`; framed group | `{"type":"card","title":"Today","children":[]}` |
| `section` | `title`, `children`, optional `description`; unframed group | `{"type":"section","title":"Recent activity","children":[]}` |
| `divider` | No additional fields | `{"type":"divider"}` |
| `spacer` | `size`, default `medium` | `{"type":"spacer","size":"small"}` |
| `text` | `text`, `style`, `tone` | `{"type":"text","text":"{{data.snapshot.title}}","style":"heading"}` |
| `markdown` | `text`; normal Markdown, including PlantUML fences where the host supports diagrams | `{"type":"markdown","text":"**Ready** to begin."}` |
| `badge` | `text`, `tone` | `{"type":"badge","text":"Saved","tone":"success"}` |
| `progress` | Optional `value` as a numeric string from `"0"` to `"1"` or an exact binding; omit for indeterminate activity; optional `label` | `{"type":"progress","value":"0.6","label":"3 of 5 complete"}` |
| `keyValue` | `items:[{key,value}]`, both formatted strings | `{"type":"keyValue","items":[{"key":"Duration","value":"25 min"}]}` |
| `empty` | `title`, optional `description` | `{"type":"empty","title":"No sessions yet","description":"Start your first session."}` |
| `diagram` | `source` containing PlantUML without Markdown fences, optional `title`; source shown if diagram rendering is unavailable | `{"type":"diagram","source":"@startuml\nUser -> App: Save\n@enduml"}` |
| `list` | `items` exact array binding, `item` template node, optional stable `key` path within each item and `empty` node | `{"type":"list","items":"{{data.snapshot.items}}","key":"id","item":{"type":"text","text":"{{item.label}}"}}` |
| `table` | `rows` exact array binding, `columns:[{title,value,weight?}]`, optional `key`, `onRowClick`, `empty` text; positive column weights | `{"type":"table","rows":"{{data.snapshot.items}}","key":"id","columns":[{"title":"Name","value":"{{item.label}}","weight":2}],"empty":"Nothing yet"}` |
| `if` | `when` exact binding, `then` node, optional `else` node | `{"type":"if","when":"{{state.details}}","then":{"type":"text","text":"Details are visible"}}` |
| `button` | Required nonblank `label` and `action`; optional `style`, `icon`, `enabledWhen`, `confirm`, `id` | `{"type":"button","label":"Refresh","icon":"refresh","action":{"type":"reload","source":"snapshot"}}` |
| `textField` | `bind` names a declared state key; optional `label`, `placeholder`, `multiline:false`; edits a string | `{"type":"textField","bind":"note","label":"Note","multiline":true}` |
| `toggle` | `bind`, `label`; edits a boolean | `{"type":"toggle","bind":"reminders","label":"Reminders"}` |
| `select` | `bind`, nonempty `options:[{value,label}]` with unique string values, optional `label` | `{"type":"select","bind":"period","label":"Period","options":[{"value":"week","label":"This week"},{"value":"month","label":"This month"}]}` |
| `native` | `widget:date|file|folder`, `bind`, optional `label`, `extensions` (file only) | `{"type":"native","widget":"date","bind":"day","label":"Date"}` |
| `web` | `html`, optional `bridge:false`, `data` exact binding, `height:compact|regular|tall|content` | See `service-as-ui/webview` for the complete mode and example. |

`gap`/`size`: `none`, `small`, `medium`, `large`. A `card` or `section` has no `gap`; put a `column` inside it when you need different child spacing.

`row.wrap:true` allows the host to fit children to available width. Ordinary surfaces flow intrinsic-width children onto new lines; the full app can stack the row vertically at compact width. Do not rely on wrapping rows for fixed equal-width columns. Check the actual host at narrow and wide sizes.

Text styles: `body` (default), `caption`, `title`, `heading`, `code`. Text/badge tones: `neutral` (default), `brand`, `success`, `warning`, `danger`. Button styles: `secondary` (default), `primary`, `ghost`, `danger`.

`enabledWhen` uses binding truthiness, not a JavaScript expression. `confirm` is a supported explicit question before an action; it is unrelated to automatic safety review. Leave it out of routine flows and honor a user's requirement for no confirmations. `id` is an optional short identifier for a button, not its visible text.

An `icon` makes an icon button: `label` remains required as its tooltip and accessible name. Supported names:

`play`, `pause`, `stop`, `refresh`, `copy`, `link`, `external`, `open`, `folder`, `file`, `code`, `terminal`, `calendar`, `search`, `filter`, `sort`, `edit`, `trash`, `download`, `upload`, `share`, `send`, `settings`, `info`, `warning`, `success`, `error`, `check`, `close`, `plus`, `minus`, `pull-request`, `merge`, `branch`, `commit`, `globe`, `database`, `cloud`, `user`, `users`, `mail`, `bell`, `star`, `heart`, `sparkles`, `plan`, `history`, `bookmark`, `tag`, `pin`, `lock`, `eye`, `chat`, `grid`.

## Platform boundaries

- The native node tree uses the host's Compose components and platform theme. It is the portable layout choice; it does not mean every operation is available on every platform.
- Harness scripts currently execute on Desktop only. A native layout backed by a harness therefore does not automatically provide a working mobile app or synchronized mobile records. Verify both rendering and owner execution before promising Android/iPad support; do not replace an existing owner merely to conceal this limitation.
- `native` date writes `YYYY-MM-DD`: Android opens a framework date dialog, iOS a date picker, and Desktop uses a validated text field. File/folder pickers write absolute paths and are Desktop-only. `extensions:["json","csv"]` uses bare extensions and applies only to a file picker.
- File saving and revealing paths are Desktop-only. A selected path is not the file's contents and does not grant a WebView filesystem access; an authorized owner method must perform any import.
- WebView documents require Desktop and enabled web documents. The host displays an unavailable note otherwise. Provide a native experience for required mobile use; do not promise that an HTML app will run there.
- `context.platform` is `desktop`, `android` or `ios`; `context.hasSession` is a boolean. Bindings cannot compare strings, so choose the appropriate spec or return computed flags from an owner method when conditional platform UI is needed.
- External links and clipboard actions use host facilities. Host context can still make an action unavailable; expose a useful error instead of a silent button.
