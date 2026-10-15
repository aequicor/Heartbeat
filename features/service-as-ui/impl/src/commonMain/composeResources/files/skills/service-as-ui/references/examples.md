# Complete examples

Load with `ui_reference(topic="service-as-ui/examples")`. The fenced JSON blocks below are complete version-1 specs, not tool calls. Owner methods and companion surfaces are explicit prerequisites; example data is illustrative and must not be substituted for a user's existing records.

To save an example, pass it as the object `spec` in `ui_put`, along with the actual `owner` from `ui_list`, a stable `name`, the indicated `placement`, and a human title. For an existing surface, use its current `expectedRevision`. Then use `ui_show` with the returned full ID and title when a chat entry is needed. See `service-as-ui/actions-data` for all tool fields.

## Native app: a daily journal

Save as `name:"journal", placement:"card", title:"Daily journal"`. Prerequisites: owner method `snapshot` returns `{summary:string, entries:[{id,date,text}], canSave:boolean}`; `save_note({text,date})` validates and persists a note and returns `{message:string}`. Date defaults to an empty draft and should be validated by the owner. No initial sample history is written.

This combines native layout, text and date inputs, live data, status, tab-like local disclosure, conditional content and a keyed list. The owner provides `canSave`; it is not a substitute for server-side input validation.
The layout is portable, but a harness-backed implementation currently executes on Desktop only; native controls alone do not supply a mobile owner runtime or synchronization.

```json
{
  "version": 1,
  "title": "Daily journal",
  "state": {"note": "", "day": "", "showHistory": false, "result": null},
  "data": {"snapshot": {"method": "snapshot", "load": "auto"}},
  "body": {
    "type": "column",
    "gap": "medium",
    "children": [
      {"type": "text", "text": "Daily journal", "style": "heading"},
      {"type": "text", "text": "{{data.snapshot.summary}}", "style": "caption"},
      {"type": "if", "when": "{{status.snapshot.loading}}", "then": {"type": "progress", "label": "Loading journal…"}},
      {"type": "if", "when": "{{status.snapshot.failed}}", "then": {
        "type": "column", "gap": "small", "children": [
          {"type": "text", "text": "{{status.snapshot.error}}", "tone": "danger"},
          {"type": "button", "label": "Retry", "action": {"type": "reload", "source": "snapshot"}}
        ]
      }},
      {"type": "row", "wrap": true, "gap": "small", "children": [
        {"type": "button", "label": "Write", "action": {"type": "set", "key": "showHistory", "value": false}},
        {"type": "button", "label": "History", "action": {"type": "set", "key": "showHistory", "value": true}}
      ]},
      {"type": "if", "when": "{{state.showHistory}}", "then": {
        "type": "list", "items": "{{data.snapshot.entries}}", "key": "id",
        "item": {"type": "section", "title": "{{item.date}}", "children": [{"type": "text", "text": "{{item.text}}"}]},
        "empty": {"type": "empty", "title": "No notes yet", "description": "Your saved notes will appear here."}
      }, "else": {
        "type": "column", "gap": "small", "children": [
          {"type": "native", "widget": "date", "bind": "day", "label": "Date"},
          {"type": "textField", "bind": "note", "label": "Note", "multiline": true},
          {"type": "button", "label": "Save note", "style": "primary", "enabledWhen": "{{data.snapshot.canSave}}", "action": {
            "type": "call", "method": "save_note", "args": {"text": "{{state.note}}", "date": "{{state.day}}"}, "into": "result"
          }},
          {"type": "text", "text": "{{state.result.message}}", "style": "caption"}
        ]
      }}
    ]
  }
}
```

Keep the draft until successful persistence is known; this example deliberately does not clear the note after clicking. A complete app can expose an explicit new-note action or reconcile returned values.

## Context summary linked to the same app

Save as `name:"journal_context", placement:"context", title:"Journal summary"` under the same owner as the existing `journal` surface. Prerequisite: read-only `summary` returns `{today:string, total:string, progress:number, progressLabel:string}`. This is a companion summary, not another full app. The current context must support focusing the app and accepting draft prompts.

```json
{
  "version": 1,
  "data": {"summary": {"method": "summary"}},
  "body": {"type": "column", "gap": "small", "children": [
    {"type": "text", "text": "Your journal", "style": "title"},
    {"type": "keyValue", "items": [
      {"key": "Today", "value": "{{data.summary.today}}"},
      {"key": "Saved notes", "value": "{{data.summary.total}}"}
    ]},
    {"type": "progress", "value": "{{data.summary.progress}}", "label": "{{data.summary.progressLabel}}"},
    {"type": "button", "label": "Open journal", "style": "primary", "action": {"type": "focusCard", "surface": "journal"}},
    {"type": "button", "label": "Draft a reflection request", "style": "ghost", "action": {"type": "prompt", "text": "Help me reflect on my recent journal entries.", "submit": false}}
  ]}
}
```

## Desktop import and export dialog

Save as `name:"journal_files", placement:"modal", title:"Journal files", launchers:["composer"]`. Prerequisites: `inspect_file({path,mode,includeArchived})` validates and reads a user-selected file without importing it, returning `{summary,csv}`. `export_folder({path})` writes an authorized export to the selected folder and returns `{summary}`. Implement actual import as a separate explicit owner operation if requested; this example only inspects a file and exports.

This demonstrates file/folder pickers, select, toggle, copy, save and reveal. All file operations are Desktop-only; `reveal` requires an existing selected path. The host provides the dialog's close control; the `close` action is only for named local modals opened with `open.modal`.

```json
{
  "version": 1,
  "state": {"path": "", "folder": "", "mode": "csv", "includeArchived": false, "result": null},
  "body": {"type": "column", "gap": "medium", "children": [
    {"type": "native", "widget": "file", "bind": "path", "label": "Source file", "extensions": ["csv", "json"]},
    {"type": "select", "bind": "mode", "label": "Format", "options": [{"value": "csv", "label": "CSV"}, {"value": "json", "label": "JSON"}]},
    {"type": "toggle", "bind": "includeArchived", "label": "Include archived entries"},
    {"type": "button", "label": "Inspect file", "enabledWhen": "{{state.path}}", "action": {"type": "call", "method": "inspect_file", "args": {"path": "{{state.path}}", "mode": "{{state.mode}}", "includeArchived": "{{state.includeArchived}}"}, "into": "result"}},
    {"type": "text", "text": "{{state.result.summary}}"},
    {"type": "row", "wrap": true, "children": [
      {"type": "button", "label": "Copy summary", "enabledWhen": "{{state.result.summary}}", "action": {"type": "copy", "text": "{{state.result.summary}}"}},
      {"type": "button", "label": "Save CSV…", "enabledWhen": "{{state.result.csv}}", "action": {"type": "saveFile", "text": "{{state.result.csv}}", "name": "journal.csv", "extensions": ["csv"]}},
      {"type": "button", "label": "Show source file", "enabledWhen": "{{state.path}}", "action": {"type": "reveal", "path": "{{state.path}}"}}
    ]},
    {"type": "divider"},
    {"type": "native", "widget": "folder", "bind": "folder", "label": "Export folder"},
    {"type": "button", "label": "Export to folder", "enabledWhen": "{{state.folder}}", "action": {"type": "call", "method": "export_folder", "args": {"path": "{{state.folder}}"}, "into": "result"}}
  ]}
}
```

## Mixed native table and WebView detail

Save as `name:"activity", placement:"panel", title:"Activity", launchers:["context"]` only when a side workspace fits the user's request. Prerequisite: `activity` returns `[{id,label,summary}]`. The manual source loads from the Refresh button. Clicking a row opens a local wide modal and passes the whole selected object to its one WebView. That WebView has no bridge because it only displays data.

```json
{
  "version": 1,
  "data": {"activity": {"method": "activity", "load": "manual"}},
  "modals": {"detail": {"title": "Activity detail", "wide": true, "body": {
    "type": "web", "height": "content", "bridge": false, "data": "{{args.record}}",
    "html": "<!doctype html><html lang=\"en\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>body{margin:0;background:var(--hb-surface);color:var(--hb-text)}main{padding:var(--hb-space-l)}h1{font-size:var(--hb-title-size);padding-inline-end:var(--hb-overlay-width,0px);min-height:var(--hb-overlay-height,0px)}p{white-space:pre-wrap}</style><main data-heartbeat-root><h1 id=\"title\"></h1><p id=\"summary\"></p></main><script>function render(d){document.getElementById('title').textContent=d?.label??'Activity';document.getElementById('summary').textContent=d?.summary??''}render(heartbeat.data);window.addEventListener('heartbeat:data',e=>render(e.detail));</script></html>"
  }}},
  "body": {"type": "column", "children": [
    {"type": "row", "children": [
      {"type": "text", "text": "Activity", "style": "heading"},
      {"type": "button", "label": "Refresh activity", "icon": "refresh", "action": {"type": "reload", "source": "activity"}}
    ]},
    {"type": "if", "when": "{{status.activity.loading}}", "then": {"type": "progress", "label": "Loading…"}},
    {"type": "if", "when": "{{status.activity.failed}}", "then": {"type": "text", "text": "{{status.activity.error}}", "tone": "danger"}},
    {"type": "table", "rows": "{{data.activity}}", "key": "id", "columns": [
      {"title": "Activity", "value": "{{item.label}}", "weight": 2},
      {"title": "Summary", "value": "{{item.summary}}", "weight": 3}
    ], "empty": "Refresh to load activity", "onRowClick": {"type": "open", "modal": "detail", "args": {"record": "{{item}}"}}}
  ]}
}
```

For a full custom app, use the HTML pattern in `service-as-ui/webview` as the root body of a canonical `card`, with a snapshot source and `bridge:true`. For an anonymous one-off result, pass a native spec directly to `ui_show(spec=...)` and remove all owner sources/calls; it remains a snapshot even if its local controls are interactive.
