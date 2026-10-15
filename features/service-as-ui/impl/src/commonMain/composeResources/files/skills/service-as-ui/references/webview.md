# Isolated WebView surfaces

Load with `ui_reference(topic="service-as-ui/webview")`. Use this mode for custom mini-app composition, visual hierarchy, navigation and interaction on Desktop, not only for features impossible in native nodes. The native JSON layout remains useful for simple host UI and portability. `mini-app-design` guides the visual direction; the mechanics below support it.

## Node and access

A `web` node contains `html`, optional `bridge` (default false), optional `data` (exact binding), and `height` (`compact`, `regular`, `tall`, `content`; default regular). Only one is allowed in the whole spec, counting its body and all named modals.

Owner-backed library surfaces may use WebView in their body or a modal, including `card` and `composer` placements. Anonymous specs may include it only inside a modal and never with a bridge. Editor previews do not execute the owner bridge. Desktop and enabled web documents are required; otherwise the host shows an unavailable note.

A document has an isolated origin and in-memory browser context. **Safe Mode is on by default** (`service_ui.web_safe_mode`, in the existing settings toggle panel, shared by all profiles). It blocks external resources; inline CSS/JavaScript/SVG/canvas and `data:` images/fonts work. No framework or icon library is preinstalled, so class names without CSS do nothing.

When the user disables Safe Mode, HTTP(S) scripts, stylesheets, fonts, images and media may load, including CDN dependencies. Use versioned production builds and the actual required CSS; evaluate loading failures and test with `ui_preview`. External scripts run in the page: they can read its data and call the owner methods allowed to its existing bridge. Asset requests can transmit data. Automatic review examines the supplied code and URLs; it does not fetch, audit or guarantee the safety of third-party code. The owner-method allowlist is unchanged.

Both modes block fetch/XHR, sockets, WebRTC, frames, workers, form submissions, navigation away, popups and arbitrary local files. A local form can handle `submit` with `preventDefault()` and use an authorized owner method. Do not place credentials in HTML. Service IO belongs in owner methods. The agent cannot choose the policy in HTML, JSON or tool arguments. Changing the setting recreates active mini-app documents and cancels previews; unsaved input may reset. Editor, live app and `ui_preview` use the same effective setting.

The complete HTML string, including inline code, CSS, fonts and embedded assets, fits **1 MiB UTF-8**; the complete JSON spec fits **2 MiB**, and the profile library holds up to **16 MiB**. These resource bounds are independent of Safe Mode. Account for JSON escaping and actual compiled sizes; model context and automatic-review capacity may limit very large bundles earlier. Vanilla code can deliver a professional design. Host tokens and basic controls provide a foundation; choose composition and hierarchy for the product. The [visual patterns](visual-patterns.md) topic supplies a self-contained interaction recipe.

## Preview a draft before saving

When exposed, `ui_preview` renders a supplied `html` string with the same WebView page/bootstrap, theme tokens and resource policy used by editor web previews. It requires Desktop with interface web documents enabled, but no project, library surface or owner. It creates a disposable document, returns bounded layout diagnostics, and changes no saved app or owner data. With Safe Mode off, automatic safety review must allow the exact draft and current policy before any resource loads; refusal returns to the agent without a manual approval flow.

Arguments: `html` (required, at most 1 MiB), optional `data` (sample JSON supplied directly as `heartbeat.data`), `width` (320–1600 CSS px, default 900), `height` (240–1200 CSS px, default 640), `theme` (`light` or `dark`, default light), `fontScale` (0.8–2, default 1), and `includeScreenshot` (default false). Data is limited to 512 KiB and nesting depth 64. No URL, owner, bridge, native JSON spec or arbitrary evaluation command is accepted.

The default result is text-only so text-only models can use it. Set `includeScreenshot:true` only when the current model accepts image tool results; the response then includes its PNG directly. Inspect that image rather than declaring success from an empty issue list. Geometry and DOM diagnostics are useful evidence about specific defects, **not visual or aesthetic approval**. The report identifies the effective resource policy, theme/text scale, image inclusion, actual viewport/content bounds, HTML bytes and any truncated diagnostic list. Up to 20 visible element samples include bounding rectangles, font family/size/line height and computed text/background colors without field values or page text; compare these to diagnose alignment and typography even on a text-only model. These bounded samples are not the complete DOM or a contrast audit.

Use realistic empty/populated/long sample data, rerender at narrow and wide widths, then check dark theme and increased text scale. The screenshot is the requested viewport, not a full-page or host-window capture. Each call starts a fresh document: preview does not click through flows, keep drafts between calls, load owner snapshots, grant `heartbeat.call`, simulate saving, or verify the complete host shell/overlay. Prepare a draft/sample state for the view being inspected and use the live app for save/reopen checks. If rendering is unavailable or the model cannot inspect images, report that gap instead of claiming visual verification.

## Bridge and lifecycle

- Initial data is `heartbeat.data`. Read later values from `window`'s `heartbeat:data` event, using `event.detail`.
- With `bridge:true`, `await heartbeat.call(method, args)` invokes a published owner method. `args` is an object. It returns the method value or rejects with an `Error` containing the owner's message.
- It is not a general tool API: there is no supported `heartbeat.open`, file API or arbitrary tool execution. Native actions can surround the WebView when host operations are needed.
- Call mutation methods from user actions, keep an in-flight guard, and display errors near the action. Retain the same operation ID for a retry if a write's outcome is uncertain and the owner supports deduplication.
- Owner-data changes refresh auto sources and deliver new data to the page. Merge new saved state without replacing focused inputs or clearing local drafts. Avoid snapshot polling after every call.
- Hiding/reopening the canonical app and moving it between start and modal retain the document and drafts within the same owner/context. Changing its HTML/spec/owner or leaving that context can replace it. Durable records and deadlines belong in owner storage.
- `window.alert`, `confirm` and `prompt` are blocked. Render status and errors inline. A button must not depend on a blocked confirmation returning true.

## Sizing and scroll ownership

`compact`, `regular` and `tall` are bounded embedded viewport presets. `content` measures the body or an element marked `data-heartbeat-root` and grows/shrinks within the host bounds.

The canonical full app uses its host presentation rather than that preset: start fills the available app region; its modal follows content height, capped by the screen, and shrinks again on a shorter tab. Keep normal document flow and one page scroll when content exceeds the cap. Avoid fixed page heights, `min-height:100vh`, artificial bottom space or nested scrolling cards.

The canonical mini-app Start surface and modal with `web` as their root are edge-to-edge within the host's rounded outline. The host overlays an expand button on Start and a close button in the modal at the top end, without another padded title bar. CSS variables `--hb-overlay-width` and `--hb-overlay-height` report the occupied area in CSS pixels (zero outside these presentations). Account for the area in the header or toolbar, without adding a blank full-width strip. Do not draw a second outer frame, shadow, expand or close button. Named local modals opened with `open.modal` retain the host's title, padding and close action; their WebView content should fit that framed dialog.

## Host theme tokens

The host supplies a system-sans body, inherited form fonts and basic control styling. Use semantic tokens so light/dark theme changes work without reloading. These defaults provide a foundation; they do not choose the app's composition, hierarchy or visual identity. Theme-aware custom accents, artwork and an intentional type hierarchy are supported when their contrast and scaling remain usable.

| Role | CSS variables |
|---|---|
| Surfaces and text | `--hb-surface`, `--hb-surface-raised`, `--hb-text`, `--hb-text-muted`, `--hb-border` |
| Accent and controls | `--hb-accent`, `--hb-on-accent`, `--hb-accent-muted`, `--hb-control`, `--hb-input`, `--hb-focus` |
| Feedback | `--hb-danger`, `--hb-on-danger`, `--hb-success`, `--hb-on-success` |
| Typography | `--hb-font-family`, `--hb-font-size`, `--hb-line-height`, `--hb-body-size`, `--hb-body-line-height`, `--hb-title-size`, `--hb-caption-size` |
| Spacing and control size | `--hb-space-xs`, `--hb-space-s`, `--hb-space-m`, `--hb-space-l`, `--hb-space-xl`, `--hb-control-height` |
| Control shape, focus and interaction | `--hb-control-radius`, `--hb-field-radius`, `--hb-focus-width`, `--hb-focus-offset`, `--hb-control-hover`, `--hb-control-pressed` |
| Start expand / modal close overlay | `--hb-overlay-width`, `--hb-overlay-height` |

`heartbeat.theme` contains `colorScheme` (`light` or `dark`) and `variables`. Listen to `heartbeat:theme` if a canvas or other non-CSS view needs a repaint. The CSS variables update directly; do not infer the app theme only from `prefers-color-scheme`. Theme changes neither change data nor grant method access.

Use `--hb-font-size` / `--hb-line-height` for controls and labels, and `--hb-body-size` / `--hb-body-line-height` for explanations and empty states. Reserve `--hb-caption-size` for secondary metadata; do not make meaningful text smaller. These tokens include the user's font scale. Use `--hb-text` and `--hb-text-muted` without extra opacity, and check text contrast against its actual background in both themes (at least 4.5:1 for normal text). Avoid tiny uppercase labels with wide letter spacing. Keep implementation details such as storage paths out of the working UI.

## Responsive charts and drawings

Expansion changes the viewport of the **same document**; it does not rerun initialization. A chart drawn only on load can become blurred or horizontally stretched when CSS resizes its old bitmap. Render labels, legends and empty states as HTML where possible so they retain normal text proportions and accessibility.

- For canvas, observe the chart container with `ResizeObserver`. Set its CSS size from the layout and its backing `width`/`height` to the measured CSS size multiplied by `devicePixelRatio`. Reset the transform with `setTransform` and redraw from current data after resizing; changing either backing dimension clears the canvas and its context state. Never stretch a stale bitmap or accumulate `scale()` calls on every draw.
- Redraw on data, theme, container size and device-pixel-ratio changes, including moving between monitors and returning from a hidden tab. Skip zero-sized containers and let the observer redraw when visible again. Do not change the observed container's size from the drawing callback.
- For SVG, retain its aspect ratio (`viewBox` with the default `xMidYMid meet`); do not stretch text with `preserveAspectRatio="none"` or nonuniform CSS transforms. If a chart must fill independent width/height constraints, recompute plot coordinates for the measured viewport while keeping labels at the host font size.
- Check the same page at narrow and wide widths, Start → expanded modal → Start, both themes and different display scales. Text must remain readable, circles round, and canvas X/Y pixel scales equal; draft input and the selected tab must survive.

This standalone chart example uses `{values:number[]}` as its illustrative `heartbeat.data` snapshot. Adapt that data shape to the owner; it does not write records or call methods. The host still measures natural document height.

```html
<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body { margin: 0; color: var(--hb-text); background: var(--hb-surface); }
main { padding: var(--hb-space-l); }
header { padding-inline-end: var(--hb-overlay-width, 0px); min-height: var(--hb-overlay-height, 0px); }
h1 { font-size: var(--hb-title-size); margin: 0 0 var(--hb-space-m); }
.plot { position: relative; width: 100%; height: 12rem; }
canvas { display: block; width: 100%; height: 100%; }
.empty {
  position: absolute; inset: 0; margin: 0; padding: var(--hb-space-l);
  display: grid; place-items: center; text-align: center; color: var(--hb-text-muted);
  font-size: var(--hb-body-size); line-height: var(--hb-body-line-height);
}
.empty[hidden] { display: none; }
</style>
<main data-heartbeat-root>
  <header><h1>Progress</h1></header>
  <div class="plot">
    <canvas role="img" aria-label="Progress chart" aria-describedby="chart-summary"></canvas>
    <p class="empty">No records yet. Add your first entry to see progress.</p>
  </div>
  <p id="chart-summary" aria-live="polite"></p>
</main>
<script>
const plot = document.querySelector('.plot');
const canvas = plot.querySelector('canvas');
const empty = plot.querySelector('.empty');
const summary = document.getElementById('chart-summary');
const ctx = canvas.getContext('2d');
let values = [], pending = false;
function draw() {
  pending = false;
  const {width, height} = plot.getBoundingClientRect();
  if (width <= 0 || height <= 0) return;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.round(width * dpr));
  canvas.height = Math.max(1, Math.round(height * dpr));
  ctx.setTransform(canvas.width / width, 0, 0, canvas.height / height, 0, 0);
  if (!values.length) return;
  const color = getComputedStyle(document.documentElement).getPropertyValue('--hb-accent').trim();
  ctx.strokeStyle = color; ctx.fillStyle = color; ctx.lineWidth = 2;
  const low = Math.min(...values), high = Math.max(...values), range = high - low || 1;
  const inset = 16;
  const points = values.map((value, index) => ({
    x: values.length === 1 ? width / 2 : inset + index / (values.length - 1) * (width - inset * 2),
    y: high === low ? height / 2 : height - inset - (value - low) / range * (height - inset * 2)
  }));
  ctx.beginPath();
  points.forEach((point, index) => index ? ctx.lineTo(point.x, point.y) : ctx.moveTo(point.x, point.y));
  ctx.stroke();
  for (const point of points) {
    ctx.beginPath(); ctx.arc(point.x, point.y, 3, 0, Math.PI * 2); ctx.fill();
  }
}
function schedule() {
  if (!pending) { pending = true; requestAnimationFrame(draw); }
}
function receive(data) {
  values = Array.isArray(data?.values) ? data.values.filter(Number.isFinite) : [];
  empty.hidden = values.length > 0;
  summary.textContent = values.length ? 'Recorded values: ' + values.join(', ') : '';
  schedule();
}
new ResizeObserver(schedule).observe(plot);
window.addEventListener('resize', schedule);
window.addEventListener('heartbeat:theme', schedule);
window.addEventListener('heartbeat:data', event => receive(event.detail));
document.addEventListener('visibilitychange', () => { if (!document.hidden) schedule(); });
function watchDensity() {
  const query = matchMedia('(resolution: ' + (window.devicePixelRatio || 1) + 'dppx)');
  query.addEventListener('change', () => { watchDensity(); schedule(); }, {once: true});
}
watchDensity();
receive(heartbeat.data);
</script>
</html>
```

## Minimal working page

This HTML is the value of a `web.html` field, JSON-encoded as a string. Pair it with `bridge:true`, `data:"{{data.snapshot}}"`, `height:"content"`, and a read-only `snapshot` source. The illustrative owner publishes `save_note({text})` and `snapshot` returns `{note:string}`. Adapt those methods to the actual owner; this example does not create them.

```html
<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
* { box-sizing: border-box; }
body { margin: 0; color: var(--hb-text); background: var(--hb-surface); }
main { padding: var(--hb-space-l); }
header {
  display: flex; align-items: center;
  padding-inline-end: var(--hb-overlay-width, 0px);
  min-height: var(--hb-overlay-height, 0px);
}
h1 { font-size: var(--hb-title-size); margin: 0 0 var(--hb-space-m); }
label { display: block; margin-block-end: var(--hb-space-xs); }
textarea {
  width: 100%; padding: var(--hb-space-s); border-radius: var(--hb-field-radius);
  border: 1px solid var(--hb-border); color: var(--hb-text);
  background: var(--hb-input); resize: vertical;
}
button {
  min-height: var(--hb-control-height); padding-inline: var(--hb-space-m);
  border: 0; border-radius: var(--hb-control-radius); background: var(--hb-accent);
  color: var(--hb-on-accent); cursor: pointer;
}
button:disabled { opacity: .6; cursor: default; }
:focus-visible { outline: 2px solid var(--hb-focus); outline-offset: 2px; }
.actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--hb-space-s); margin-block-start: var(--hb-space-s); }
#status { color: var(--hb-text-muted); }
</style>
<main data-heartbeat-root>
  <header><h1>Session note</h1></header>
  <label for="note">Note</label>
  <textarea id="note" rows="3"></textarea>
  <div class="actions"><button id="save" type="button">Save note</button><span id="status" role="status"></span></div>
</main>
<script>
const input = document.getElementById('note');
const button = document.getElementById('save');
const status = document.getElementById('status');
let dirty = false, busy = false;
function receive(data) {
  if (!dirty && document.activeElement !== input) input.value = data?.note ?? '';
}
input.addEventListener('input', () => { dirty = true; });
window.addEventListener('heartbeat:data', event => receive(event.detail));
receive(heartbeat.data);
button.addEventListener('click', async () => {
  if (busy) return;
  busy = true; button.disabled = true; status.textContent = 'Saving…';
  const submitted = input.value;
  try {
    await heartbeat.call('save_note', {text: submitted});
    dirty = input.value !== submitted;
    status.textContent = dirty ? 'Saved. Your newer edit is not saved yet.' : 'Saved';
  } catch (error) {
    status.textContent = error.message || 'Could not save. Your note is still here.';
  } finally {
    busy = false; button.disabled = false;
  }
});
</script>
</html>
```

This pattern keeps the input DOM and draft intact during updates. For record creation, add owner-side deduplication rather than treating a disabled button as the only duplicate safeguard. Validate scroll, keyboard focus, close-overlay clearance, theme changes and actual save/reopen behavior in the live host; HTML rendering in a standalone browser does not verify the owner bridge.
