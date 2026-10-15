---
name: mini-app-design
description: Design, build, and improve mini-apps inside Heartbeat with complete task flows, durable behavior, efficient layouts, platform-native interaction, and a distinctive visual style.
---

# Mini-App Design

Build a focused, professionally finished mini-app that lets the user complete a task, understand the result, and continue later. Include a coherent visual direction and polished interaction by default; the user need not separately request beauty. Preserve a requested style. Otherwise infer a suitable character from the product, audience and task, then make deliberate choices for composition, typography, color and artwork instead of stopping at a functional scaffold.

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

Use declarative host components for simple forms, lists, quick actions and portable host UI. Choose WebView for a full custom mini-app when its visual hierarchy, composition, navigation or interaction benefits from HTML/CSS, even if native nodes could technically approximate it. Neither mode guarantees design quality. Choose for the actual product rather than forcing every app into a form, dashboard, card grid or tab bar. Do not expose raw JSON as a substitute for a usable control. Check platform support first: WebView and harness scripts currently run on Desktop; responsive HTML alone does not enable Android or iOS execution. State actual limitations instead of presenting placeholders as working features.

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

Integrate with the host's theme, text scaling, overlay controls, focus, selection, field editing and modal return behavior. Host integration leaves room for the app's own composition, type hierarchy, theme-aware custom accents and artwork. Use host tokens as a reliable foundation, not as a requirement for one uniform aesthetic. Let the host provide window controls.

- Establish a visual direction before writing markup: the product's tone and strongest content, a purposeful composition, readable primary/secondary text, a controlled palette with accent and semantic feedback, a heading/body/label/metadata type hierarchy, and consistent field heights/gutters. Use contrast, scale, grouping and alignment to create a recognizable hierarchy. Choose decoration and imagery that reinforce the product; adapt host tokens or define theme-aware accents while checking the resulting combinations. Do not treat a neutral form as the universal design brief.
- Sketch the working hierarchy: app heading and context → task navigation if needed → current task → supporting information. Choose one main action per task. Show the selected navigation destination with a persistent visual and accessible state; keep its order and labels stable, and retain drafts when switching. Do not style navigation as a row of competing primary actions.
- Treat a field as a group: visible label, appropriately sized control, optional unit/helper, inline validation message. Place related short fields side by side only while each remains usable; keep long text fields full width. A placeholder is not a label. Check default, focus, invalid, disabled and pending states; keep error text readable and attach it to the field.
- Put the current useful action first. Make the primary action distinct within its working context. Add navigation for different tasks, not for a few lines that belong together.
- Use one app heading, a clear text hierarchy, and consistent alignment. Account for the surrounding surface: avoid an outer card inside an already framed card. Adjacent related panels should align at the top. Preserve consistent gutters between surfaces and navigation; check the host's spacing before adding another inset inside the document.
- Use available width before adding height. On wide layouts, put row details, related inputs, and their action together. On narrow layouts, stack groups in task order. Preserve readable text and comfortable hit areas. Keep useful names and control labels visible when there is room; collapse them according to available width, not merely because another surface is present.
- Let content determine height. Remove empty spacers, stretched rows, and oversized promotional headers above working forms. Space separates meaningful groups; it should not fill an unused screen. Keep long content and the last action reachable.
- Express the chosen direction through consistent accents, artwork and details. A space theme can use an orbital motif and palette while keeping forms legible. Rich styling does not require a separate request; its amount should fit the task. Avoid decorative clutter and generic dashboard filler.
- Use readable proportional UI text and tabular figures for numeric comparisons. The host system font is a dependency-free baseline; intentional hierarchy, weight and rhythm matter more than importing a fashionable font. An embedded custom typeface can support the design if readable and responsive to host scaling. Give icon-only buttons accessible names. Do not rely on hover for essential information or actions.
- Keep controls and important labels at least the host UI type size, explanations at the body size, and secondary metadata at least the caption size. In WebView, use `--hb-font-size` / `--hb-line-height`, `--hb-body-size` / `--hb-body-line-height`, and `--hb-caption-size`; preserve their response to host text scaling. These roles are readability anchors, not a ceiling on expressive headings: derive larger heading sizes from the host tokens where the composition benefits. Fit narrow layouts by wrapping or rearranging content, without shrinking text below these roles. Avoid tiny uppercase text and wide letter spacing for important labels, units, and instructions.
- Keep normal text contrast at least 4.5:1 against its actual background in both light and dark themes. Use `--hb-text` and `--hb-text-muted` directly for their intended roles; extra opacity or blending can make otherwise readable tokens fail contrast. Check any custom foreground/background combinations, including empty states, inputs, captions, and selected controls.
- Support visible focus, logical Tab order, keyboard activation, and states distinguishable beyond color. Accommodate touch, text scaling, reduced motion, and the on-screen keyboard on supported platforms.

## When using WebView

Read `ui_reference(topic="service-as-ui/webview")` for the bridge, theme, and layout contract. For a custom form or multi-view app, also load `ui_reference(topic="service-as-ui/visual-patterns")`: it supplies an adaptable, self-contained HTML pattern with responsive fields, navigation, validation and saved-state handling. WebView is an option, not the default for every mini-app.

- Use the documented `--hb-*` variables and live theme updates. Keep fonts, styles, scripts, and assets inside the isolated document; external networks and CDNs are unavailable. Tailwind, React and icon libraries are not preinstalled in the page; class names alone do not supply styles. Inline CSS/JavaScript/SVG work. If using a library, embed its required compiled code/assets within the document and size limits; no CDN, imports or runtime package loader. Inline SVG and CSS suit simple artwork.
- A root WebView fills the canonical mini-app Start surface and modal without host padding or a second title bar. The host supplies rounding and an overlaid expand control on Start or close control in the modal. Account for `--hb-overlay-width` and `--hb-overlay-height` in the header's end area; do not reserve an empty strip across the whole page. Named local dialogs opened with `open.modal` retain the host's title, padding and close action.
- Let the host measure natural content height and cap it to the available window. Fixed page heights or `min-height: 100vh` can leave empty space and prevent the modal from shrinking after a tab change.
- Reflow charts when the retained document changes size. A canvas needs a resized backing bitmap and a redraw at the current `devicePixelRatio`; changing only its CSS size stretches its pixels and text. SVG must preserve its aspect ratio or recompute geometry for the new viewport; `preserveAspectRatio="none"` distorts labels. Prefer HTML for chart labels and empty states. Follow the responsive chart example in `ui_reference(topic="service-as-ui/webview")` and handle size, theme, data, and pixel-density changes.
- Give the main content one vertical scroll owner. Avoid nested scrollable lists unless they serve a separate interaction. Check wheel, trackpad, keyboard scrolling, and the last action. Open the full view through the host rather than creating another window.
- Use `heartbeat.call`, initial `heartbeat.data`, and `heartbeat:data` updates. Reconcile snapshots with drafts without replacing focused inputs. Handle bridge errors inside the page; native JavaScript dialogs and pop-ups are blocked.
- Use semantic HTML controls. Check stable pointer feedback inside buttons, over the close control, and across the WebView/native boundary. Do not mask a host cursor defect with a global forced cursor or an overlay over controls.

## Verify the result proportionally

For implementation, exercise the main flow through real methods: input → save → visible result → close/reopen → continue. Check correction, repeated clicks, and important invalid inputs. Use isolated test records when a check would change the user's history.

Inspect the rendered interface at normal viewing size, not only its source. Review hierarchy and alignment first, then field/navigation states, contrast and typography. Use short and long data, wide and narrow available space, and applicable light/dark themes. Look for repeated headings, wasted space, clipped content, horizontal scrolling, and unreachable actions. Check small and large modal contents, height changes, closing, and focus return. Correct visible defects before calling the implementation complete; if no visual inspection is available, report that specific gap.

Use screenshot and interaction tools only when they are actually exposed and support the target. With Heartbeat's `computer_*` tools, start with `computer_status`, choose a permitted test/preview window from `computer_windows`, then `computer_capture`; inspect its returned image, use `computer_screenshot` after changes and `computer_zoom` for small text, and `computer_release` when done. Heartbeat's own windows cannot be captured with those tools, and desktop capture hides them. An external preview can check appearance and local interaction but cannot prove the real host's bridge, overlay or persistence. If tools are absent, disabled or cannot inspect this target, do not invent `ui_screenshot` or claim a completed visual check; state what remains unverified.

Compare the same content on Start, after expansion into the modal, and after returning. Check empty and populated charts: text should remain readable and keep its proportions while chart geometry adapts. Verify host text scaling and available standard/high-density displays or equivalent browser pixel-density settings. For canvas, confirm that both bitmap dimensions follow the rendered size and current `devicePixelRatio`, including after hiding and reopening or moving between displays. Inspect small captions and muted text at normal viewing size in both themes.

Test keyboard and pointer behavior in the running interface. When checking a WebView cursor issue, move continuously within buttons and across close controls and native boundaries. A still image cannot prove correct scrolling, data preservation, or absence of cursor flicker. Use available authorized tools and mark checks that could not be performed as unverified.

For a concept, show flows, structure, key states, and design rationale without claiming runtime verification. For a completed implementation, report what the user can do, where to open it, what was tested, and material limitations. Keep implementation details out of the working UI unless they help the user make a decision.
