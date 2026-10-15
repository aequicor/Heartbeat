---
name: mini-app-design
description: Design, build, and improve mini-apps inside Heartbeat with complete task flows, durable behavior, efficient layouts, platform-native interaction, and a distinctive visual style.
---

# Mini-App Design

Build a focused tool that lets the user complete a task, understand the result, and continue later. Let visual design support that task while preserving the character the user requested.

This skill is built into Heartbeat and available through `ui_reference(topic="mini-app-design")`, without a project or attached harness. Load `ui_reference(topic="service-as-ui")` to choose a supported surface and rendering approach; use `ui_reference(topic="spec")` for the exact current schema and limits. If the session exposes an older argument-free tool, call `ui_reference()` for the bundled entrypoints and spec.

## Design the working flow

- Identify the outcome, repeated action, data, and constraints from the request and existing context. Resolve routine layout choices yourself. Ask only when missing information could materially change the product.
- Map a short path from entry to action, visible result, and the next action or return visit. A workout might use resume → log a set → rest → finish → review history; use the actual product's workflow rather than imposing this example.
- Separate durable records, the current operation, and unsaved input. Define validation, success, and recovery for important actions. Before changing an existing app, inspect its surfaces and methods with `ui_list` and `ui_get`; retain its identity and records.
- Match functionality to the request. Analytics, settings, tabs, assistants, and integrations should earn their place. A concept request needs a concept; an implementation request needs a working flow.

## Choose the host surface

| Need | Heartbeat approach |
|---|---|
| A reusable mini-app in a conversation | One owner-backed `card` surface, displayed with `ui_show(surface=...)`. Update that surface instead of creating copies. |
| Focused full-app interaction | The host opens the same app in a modal within the current window; selecting its owner on new-chat start shows the full app there. |
| Return to the app | Use the host's composer button. Do not duplicate it with extra `composer` or `start` mirrors or status strips. |
| Additional context or quick action | Add a compact `context` surface only when it offers distinct value. |
| Simultaneous app and chat interaction | Choose a supported nonmodal placement. A `panel` can fit this workflow when compatible with the user's layout constraints. |

Keep the existing chat, input, and navigation usable. Do not populate every extension point or add side panels by default. Respect explicit restrictions on side interfaces.

Prefer declarative host components for ordinary forms, lists, and actions. Use WebView when the interaction or visual composition needs capabilities beyond the native schema. Do not expose raw JSON as a substitute for a usable control. Check platform support first: WebView and harness scripts currently run on Desktop; responsive HTML alone does not enable Android or iOS execution. State actual limitations instead of presenting placeholders as working features.

## Make behavior dependable

- Connect each visible control to a real action with a clear result. Routine operations should work without another model turn. Use labels such as “Log set”, “Resume”, and “Undo last entry”.
- Store records in the owner's durable data before reporting success. DOM state, surface state, and browser localStorage are not durable application storage. Keep snapshot methods read-only. Keep personal records and secrets out of HTML and instructions.
- Preserve unfinished input, selection, focus, and the current operation when data refreshes or views change. Replacing HTML or the surface spec may recreate the document; do not promise draft migration unless it is implemented. Preserve persistent keys and meaning during redesigns, with a lossless migration when necessary.
- Separate starting, resuming, and resetting. Validate in the owner method as well as the form. Prevent duplicate writes in the method and show pending state in the UI; use operation identifiers when a retry could create a duplicate. Never replace saved state with an error object.
- Show units, appropriate input modes, and local number/date formats. Put actionable errors next to fields and keep entered values. Offer relevant previous values for frequent operations, clearly distinguished from saved records or examples.
- Provide undo or editing for reversible mistakes. Restore timers from a saved deadline instead of counting page ticks. Background execution and notifications need separate platform support; a restored timer alone does not provide them.
- Handle applicable loading, empty, pending, success, and retryable error states. Empty history should suggest a useful next action. Do not fabricate metrics, synchronization, or notifications.
- Use the host's automatic safety review. Do not add manual permission prompts to routine actions or bypass a refusal. Correct the cause within the authorized task; stop repeating a refused operation without new evidence or a changed proposal.

## Make the interface native and expressive

Use the host's semantic colors, typography, spacing, radii, and states. Native fit also includes keyboard behavior, focus, selection, field editing, feedback, and returning from a modal. Let the host provide window controls.

- Put the current useful action first. Make the primary action distinct within its working context. Add navigation for different tasks, not for a few lines that belong together.
- Use one app heading, a clear text hierarchy, and consistent alignment. Account for the surrounding surface: avoid an outer card inside an already framed card. Adjacent related panels should align at the top. Preserve consistent gutters between surfaces and navigation; check the host's spacing before adding another inset inside the document.
- Use available width before adding height. On wide layouts, put row details, related inputs, and their action together. On narrow layouts, stack groups in task order. Preserve readable text and comfortable hit areas. Keep useful names and control labels visible when there is room; collapse them according to available width, not merely because another surface is present.
- Let content determine height. Remove empty spacers, stretched rows, and oversized promotional headers above working forms. Space separates meaningful groups; it should not fill an unused screen. Keep long content and the last action reachable.
- Express the requested style through consistent accents, artwork, and details. A space theme can use an orbital motif and palette while keeping forms legible. Rich styling is valid when requested; decoration should support the task.
- Use a proportional system UI font and tabular figures for numeric comparisons. Give icon-only buttons accessible names. Do not rely on hover for essential information or actions.
- Support visible focus, logical Tab order, keyboard activation, and states distinguishable beyond color. Accommodate touch, text scaling, reduced motion, and the on-screen keyboard on supported platforms.

## When using WebView

Read `ui_reference(topic="service-as-ui/webview")` for the bridge, theme, and layout examples. WebView is an option, not the default for every mini-app.

- Use the documented `--hb-*` variables and live theme updates. Keep fonts, styles, scripts, and assets inside the isolated document; external networks and CDNs are unavailable. Inline SVG and CSS suit simple artwork.
- A root WebView fills the canonical mini-app Start surface and modal without host padding or a second title bar. The host supplies rounding and an overlaid expand control on Start or close control in the modal. Account for `--hb-overlay-width` and `--hb-overlay-height` in the header's end area; do not reserve an empty strip across the whole page. Named local dialogs opened with `open.modal` retain the host's title, padding and close action.
- Let the host measure natural content height and cap it to the available window. Fixed page heights or `min-height: 100vh` can leave empty space and prevent the modal from shrinking after a tab change.
- Give the main content one vertical scroll owner. Avoid nested scrollable lists unless they serve a separate interaction. Check wheel, trackpad, keyboard scrolling, and the last action. Open the full view through the host rather than creating another window.
- Use `heartbeat.call`, initial `heartbeat.data`, and `heartbeat:data` updates. Reconcile snapshots with drafts without replacing focused inputs. Handle bridge errors inside the page; native JavaScript dialogs and pop-ups are blocked.
- Use semantic HTML controls. Check stable pointer feedback inside buttons, over the close control, and across the WebView/native boundary. Do not mask a host cursor defect with a global forced cursor or an overlay over controls.

## Verify the result proportionally

For implementation, exercise the main flow through real methods: input → save → visible result → close/reopen → continue. Check correction, repeated clicks, and important invalid inputs. Use isolated test records when a check would change the user's history.

Inspect short and long data, wide and narrow available space, and applicable light/dark themes. Look for repeated headings, wasted space, clipped content, horizontal scrolling, and unreachable actions. Check small and large modal contents, height changes, closing, and focus return.

Test keyboard and pointer behavior in the running interface. When checking a WebView cursor issue, move continuously within buttons and across close controls and native boundaries. A still image cannot prove correct scrolling, data preservation, or absence of cursor flicker. Use available authorized tools and mark checks that could not be performed as unverified.

For a concept, show flows, structure, key states, and design rationale without claiming runtime verification. For a completed implementation, report what the user can do, where to open it, what was tested, and material limitations. Keep implementation details out of the working UI unless they help the user make a decision.
