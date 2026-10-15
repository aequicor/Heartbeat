---
name: service-as-ui
description: Build and update interactive Heartbeat surfaces with native layouts, platform pickers, or isolated WebView content. Use for mini-apps, chat cards, contextual summaries, forms, and owner-backed actions.
---

# Service-as-UI

Turn an owner's service into a usable interface inside Heartbeat. The user can operate it without another model turn. This skill is bundled with Heartbeat and is available through `ui_reference(topic="service-as-ui")`; it requires neither a project nor an attached harness to read.

## Choose the rendering mode

| Mode | Use when | Authoring contract |
|---|---|---|
| Native layout rendered by Compose | Simple forms, lists, tables, summaries, actions, or portable host UI | A version-1 JSON tree of supported nodes. Compose is the host renderer, not a separate agent-programmable Kotlin layout language. |
| Platform-native widgets | Date input or file/folder selection should follow the host's interaction | `native` nodes inside the same JSON tree. Date uses a picker on Android/iOS and a validated text field on Desktop; file/folder picking is Desktop-only. This is not a separate layout engine. |
| WebView | A full custom mini-app with a deliberate composition, visual hierarchy, navigation or rich interaction; custom visualization | One `web` node with self-contained HTML/CSS/JavaScript, on Desktop with web documents enabled. Use the owner bridge for service operations. Native nodes need not be technically incapable before choosing this mode. |
| Mixed surface | A native form needs one custom visualization or an occasional rich dialog | Native nodes plus one `web` node in the body or a named modal. The one-WebView limit applies across the entire spec, including modals. |

Do not invent `compose`, `grid`, `tabs`, `chart`, or `image` JSON node types. Build simple tab selection with `state`, buttons and `if`; use WebView for custom grids, charts, images or more involved interactions. Preserve the user's choice of platform and extension locations.

Give implemented mini-apps a professional visual finish by default. Read `mini-app-design` to choose a direction from the product when the user has not specified a style. Host integration covers theme, text scaling and interaction boundaries; it permits a custom composition and accessible theme-aware visual identity. A stylesheet or framework alone does not provide that design work.

## Read the relevant reference

References are bundled too. Load a topic through `ui_reference`; the relative links identify the same resources.

- [Layout and placement](references/layout.md) — `service-as-ui/layout`: every placement, launcher, native node, icon and platform boundary.
- [Actions, data and tools](references/actions-data.md) — `service-as-ui/actions-data`: every action and tool, bindings, state, owner methods, persistence, revisions and limits.
- [WebView](references/webview.md) — `service-as-ui/webview`: isolation, bridge, sizing, host theme, lifecycle and a working HTML pattern.
- [Visual patterns](references/visual-patterns.md) — `service-as-ui/visual-patterns`: adaptable self-contained HTML for responsive forms, selected navigation, validation, primary/secondary actions and saved-state views; read when building a custom app layout.
- [Examples](references/examples.md) — `service-as-ui/examples`: complete specs for a native app, context summary, native file workflow and a mixed surface with a dialog.
- `ui_reference(topic="spec")` — the exact format reference for this installed version; use it to resolve validation diagnostics.
- `ui_reference(topic="mini-app-design")` — interaction and visual design guidance when creating or redesigning a mini-app.

## Build or update a surface

1. Inspect `ui_list`, then `ui_get` for an existing app. Reuse its owner and surface name when improving it. The owner's published methods and returned data are the service contract; do not assume a method exists from its name alone.
2. Choose a suitable placement and rendering mode. A full mini-app normally needs one owner-backed `card`: the host supplies its chat entry, composer button, full start view and modal. Add a `context` summary only when it provides a distinct useful action or information. Avoid duplicate composer/start copies of the same app.
3. Establish read-only snapshot methods and durable mutation methods in the owner. Use the harness tools and their API reference if a harness must be created or changed. Inspect existing durable data before changing its shape; visual redesign does not justify clearing it.
4. Use `ui_call` to inspect read methods. Test mutations only within the user's authorized task and on appropriate test data. `ui_call` performs a real operation, not a dry run.
5. Save with `ui_put`. Fix returned JSON-path diagnostics; use `expectedRevision` when replacing. Show the returned surface ID with `ui_show`. Existing live cards update after replacement; repeated `ui_show` uses the chat's single live app slot.
6. Verify the real interaction, saved state, reopen behavior, short and long content, and relevant platform/theme states. The editor preview does not execute owner methods, so preview alone cannot verify the service flow. If live verification is unavailable, state that limitation.

## Runtime boundaries

The owner must exist, be enabled, and be active in the target context for its live surface to work. Library mutations, showing surfaces and agent method calls pass the host's automatic safety review. Ordinary safe operations need no additional approval UI. A refusal is an error to diagnose, not permission to weaken the review or ask the user to bypass it. Do not add button confirmations to routine actions.

An anonymous `ui_show` spec is a snapshot with local interaction, not a substitute for an owner-backed app: it has no data sources, owner calls or WebView bridge. Use it for static results, copy/export, local disclosure and draft prompts. Keep durable records and timers in the owner's data, not solely in surface state or the DOM.
